package com.stockwatch.app

import android.util.Log
import com.stockwatch.app.core.AlertState
import com.stockwatch.app.core.AlertHit
import com.stockwatch.app.core.FUND
import com.stockwatch.app.core.FundFeed
import com.stockwatch.app.core.KlineFeed
import com.stockwatch.app.core.LISTED
import com.stockwatch.app.core.MarketSnapshot
import com.stockwatch.app.core.MarketTime
import com.stockwatch.app.core.NotificationContent
import com.stockwatch.app.core.Quote
import com.stockwatch.app.core.RuleEvaluation
import com.stockwatch.app.core.WatchConfig
import com.stockwatch.app.core.WatchItem
import com.stockwatch.app.core.buildNotification
import com.stockwatch.app.core.buildSeries
import com.stockwatch.app.core.cooldownMinutesFor
import com.stockwatch.app.core.confirmTZone
import com.stockwatch.app.core.evaluate
import com.stockwatch.app.core.evaluateProxy
import com.stockwatch.app.core.fetchQuotes
import com.stockwatch.app.core.normalizeSymbol
import com.stockwatch.app.core.planTZone
import com.stockwatch.app.core.tLevels
import java.io.File
import java.time.LocalDateTime

class WatchEngine(
    filesDir: File,
    private val notifier: Notifier,
    private val clock: () -> LocalDateTime = { MarketTime.now() },
    private val quoteFetcher: (Collection<String>) -> Map<String, Quote> = ::fetchQuotes,
    klineFeed: KlineFeed = KlineFeed(File(filesDir, "data/kline_cache.json")),
    fundFeed: FundFeed = FundFeed(File(filesDir, "data/fund_cache.json")),
) {
    private val klines = klineFeed
    private val funds = fundFeed
    private val state = AlertState(File(filesDir, "data/state.json"))

    fun runCycle(cfg: WatchConfig): Int {
        val now = clock()
        val today = MarketTime.day(now)
        var pushed = 0
        for (row in collect(cfg, now, today)) {
            val snap = row.snap
            if (snap == null || row.warn != null) {
                Log.i(TAG, "${row.item.code} 跳过：${row.warn ?: "无数据"}")
                continue
            }
            val day = if (snap.kind == FUND) snap.date else today
            val tag = if (snap.kind == FUND) "（场外净值）" else ""
            val result = evaluate(row.item, snap, cfg)
            if (result.error != null) Log.w(TAG, "${row.item.code} ${result.error}")

            val info = tLevels(row.item, snap, cfg)
            if (info != null) {
                val action = planTZone(snap, info, state, day, now, tag)
                if (action?.notification != null) {
                    val delivered = notifier.post(
                        action.notification,
                        NotifierChannels.T_RANGE,
                        snap.code,
                        "${snap.code}|t_range",
                    )
                    if (delivered) {
                        confirmTZone(state, action)
                        pushed++
                    }
                }
            }

            val proxy = row.proxySnap
            if (proxy != null) {
                val proxyResult = evaluateProxy(row.item, proxy, cfg)
                pushed += deliver(
                    item = row.item,
                    cfg = cfg,
                    snap = proxy,
                    result = proxyResult,
                    stateCode = snap.code,
                    day = today,
                    now = now,
                    cooldownKind = FUND,
                    tag = "（${snap.name} 盘中参考代理，非官方估值）",
                )
            }

            pushed += deliver(
                item = row.item,
                cfg = cfg,
                snap = snap,
                result = result,
                stateCode = snap.code,
                day = day,
                now = now,
                cooldownKind = snap.kind,
                tag = tag,
            )
        }
        return pushed
    }

    private fun collect(cfg: WatchConfig, now: LocalDateTime, today: String): List<CollectedRow> {
        val wanted = linkedSetOf<String>()
        for (item in cfg.items) {
            if (item.type != FUND && item.symbol.isNotEmpty()) wanted += item.symbol
            if (item.proxy.isNotEmpty()) {
                try {
                    wanted += normalizeSymbol(item.proxy)
                } catch (exc: IllegalArgumentException) {
                    Log.w(TAG, "${item.code} 的代理代码 ${item.proxy} 无法识别：${exc.message}")
                }
            }
        }
        val quotes = if (wanted.isEmpty()) {
            emptyMap()
        } else {
            try {
                quoteFetcher(wanted)
            } catch (exc: Exception) {
                Log.w(TAG, "场内行情获取失败：$exc")
                emptyMap()
            }
        }

        val rows = mutableListOf<CollectedRow>()
        for (item in cfg.items) {
            val kind = if (item.type == FUND) FUND else if (item.symbol in quotes) LISTED else FUND
            var snap: MarketSnapshot? = null
            var warn: String? = null
            if (kind == LISTED) {
                try {
                    snap = listedSnapshot(item.symbol, quotes.getValue(item.symbol), cfg)
                    if (snap.date != today) warn = "行情时间 ${snap.date} 非今日（停牌或未更新）"
                } catch (exc: Exception) {
                    warn = "取数失败：$exc"
                }
            } else {
                try {
                    snap = fundSnapshot(funds.snapshot(item.code, cfg.navBars))
                } catch (exc: Exception) {
                    warn = "净值获取失败：$exc"
                }
                if (snap == null && warn == null) {
                    warn = "净值接口查不到该代码（场内代码请把类型选为「场内」）"
                }
            }

            var proxySnap: MarketSnapshot? = null
            if (kind == FUND && item.proxy.isNotEmpty()) {
                try {
                    val proxySymbol = normalizeSymbol(item.proxy)
                    val quote = quotes[proxySymbol]
                    if (quote != null) {
                        proxySnap = listedSnapshot(proxySymbol, quote, cfg)
                    } else {
                        Log.w(TAG, "${item.code} 的代理 ${item.proxy} 没有实时行情")
                    }
                } catch (exc: Exception) {
                    Log.w(TAG, "${item.code} 的代理 ${item.proxy} 取数失败：$exc")
                }
            }
            rows += CollectedRow(item, kind, snap, proxySnap, warn)
        }
        return rows
    }

    private fun listedSnapshot(symbol: String, quote: Quote, cfg: WatchConfig): MarketSnapshot {
        val history = klines.closes(symbol, cfg.klineBars)
        return MarketSnapshot(
            name = quote.name,
            code = symbol.substring(2),
            kind = LISTED,
            price = quote.price,
            prevClose = quote.prevClose,
            changePct = quote.changePct,
            date = quote.date,
            timeDisplay = quote.timeDisplay,
            closes = buildSeries(history, quote.date, quote.price),
            bars = history.size,
            label = "现价",
            high = quote.high,
            low = quote.low,
        )
    }

    private fun fundSnapshot(snapshot: com.stockwatch.app.core.FundSnapshot?): MarketSnapshot? {
        snapshot ?: return null
        return MarketSnapshot(
            name = snapshot.name,
            code = snapshot.code,
            kind = FUND,
            price = snapshot.price,
            prevClose = snapshot.prevClose,
            changePct = snapshot.changePct,
            date = snapshot.date,
            timeDisplay = snapshot.timeDisplay,
            closes = snapshot.series.map { it.close },
            bars = snapshot.series.size,
            label = "净值",
            decimals = 4,
        )
    }

    private fun deliver(
        item: WatchItem,
        cfg: WatchConfig,
        snap: MarketSnapshot,
        result: RuleEvaluation,
        stateCode: String,
        day: String,
        now: LocalDateTime,
        cooldownKind: String,
        tag: String,
    ): Int {
        val grouped = linkedMapOf<String, MutableList<PendingHit>>()
        for (hit in result.hits) {
            if (hit.rule.startsWith("t_")) continue
            val key = "$stateCode|${hit.rule}|${hit.scope}"
            if (state.allow(key, day, cooldownMinutesFor(item, cfg, hit.rule, cooldownKind))) {
                val channel = if (hit.rule.endsWith("change")) {
                    NotifierChannels.CHANGE
                } else {
                    NotifierChannels.BIAS
                }
                grouped.getOrPut(channel) { mutableListOf() } += PendingHit(hit, key)
            }
        }
        var pushed = 0
        for ((channel, pending) in grouped) {
            val content = buildNotification(snap, result.detail, pending.map { it.hit.text }, now, tag)
            if (notifier.post(content, channel, stateCode, "$stateCode|$channel")) {
                for (entry in pending) state.record(entry.key, day, snap.price)
                pushed++
            }
        }
        return pushed
    }

    private data class CollectedRow(
        val item: WatchItem,
        val kind: String,
        val snap: MarketSnapshot?,
        val proxySnap: MarketSnapshot?,
        val warn: String?,
    )

    private data class PendingHit(val hit: AlertHit, val key: String)

    companion object {
        private const val TAG = "WatchEngine"
    }
}
