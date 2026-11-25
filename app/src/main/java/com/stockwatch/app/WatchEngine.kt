package com.stockwatch.app

import android.util.Log
import com.stockwatch.app.core.AlertEntry
import com.stockwatch.app.core.AlertState
import com.stockwatch.app.core.AlertHit
import com.stockwatch.app.core.Cross
import com.stockwatch.app.core.FUND
import com.stockwatch.app.core.FundFeed
import com.stockwatch.app.core.KlineFeed
import com.stockwatch.app.core.LISTED
import com.stockwatch.app.core.MarketSnapshot
import com.stockwatch.app.core.MarketTime
import com.stockwatch.app.core.NotificationContent
import com.stockwatch.app.core.Quote
import com.stockwatch.app.core.RuleEvaluation
import com.stockwatch.app.core.TSide
import com.stockwatch.app.core.WatchConfig
import com.stockwatch.app.core.WatchItem
import com.stockwatch.app.core.buildNotification
import com.stockwatch.app.core.buildSeries
import com.stockwatch.app.core.cooldownMinutesFor
import com.stockwatch.app.core.confirmTZone
import com.stockwatch.app.core.detectCross
import com.stockwatch.app.core.evaluate
import com.stockwatch.app.core.evaluateProxy
import com.stockwatch.app.core.fetchQuotes
import com.stockwatch.app.core.fmt
import com.stockwatch.app.core.itemBiasMas
import com.stockwatch.app.core.itemCross
import com.stockwatch.app.core.itemTRange
import com.stockwatch.app.core.normalizeSymbol
import com.stockwatch.app.core.planTZone
import com.stockwatch.app.core.sma
import com.stockwatch.app.core.tLevels
import com.stockwatch.app.core.tState
import java.io.File
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.round

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
    private val cycleLock = Any()
    private val overviewLock = Any()
    private var overviewAt = 0L
    private var overviewRows: List<Map<String, Any?>> = emptyList()

    @Synchronized(cycleLock)
    fun runCycle(cfg: WatchConfig): Int {
        val now = clock()
        val today = MarketTime.day(now)
        var pushed = 0
        val rows = collect(cfg, asLive = false)
        for (row in rows) {
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
        synchronized(overviewLock) {
            overviewRows = rows.map { describe(it, cfg) }
            overviewAt = System.currentTimeMillis()
        }
        return pushed
    }

    @Synchronized(overviewLock)
    fun overview(cfg: WatchConfig, force: Boolean = false): List<Map<String, Any?>> {
        val now = System.currentTimeMillis()
        if (!force && overviewAt > 0L && now - overviewAt < OVERVIEW_TTL_MS) return overviewRows
        val rows = collect(cfg, asLive = true)
        overviewRows = rows.map { describe(it, cfg) }
        overviewAt = System.currentTimeMillis()
        return overviewRows
    }

    @Synchronized(overviewLock)
    fun invalidateOverview() {
        overviewAt = 0L
    }

    fun recentAlerts(limit: Int = 30): List<AlertEntry> = state.recent(limit)

    private fun collect(cfg: WatchConfig, asLive: Boolean): List<WatchRow> {
        val now = clock()
        val today = MarketTime.day(now)
        val wanted = linkedSetOf<String>()
        for (item in cfg.items) {
            if (!item.enabled) continue
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

        val rows = mutableListOf<WatchRow>()
        for (item in cfg.items) {
            if (!item.enabled) continue
            val kind = when (item.type) {
                FUND -> FUND
                LISTED -> LISTED
                else -> if (item.symbol in quotes) LISTED else FUND
            }
            var snap: MarketSnapshot? = null
            var warn: String? = null
            if (kind == LISTED) {
                val quote = quotes[item.symbol]
                if (quote == null) {
                    warn = "实时行情查不到该场内代码"
                } else {
                    try {
                        snap = listedSnapshot(item.symbol, quote, cfg)
                        if (!asLive && snap.date != today) warn = "行情时间 ${snap.date} 非今日（停牌或未更新）"
                    } catch (exc: Exception) {
                        warn = "取数失败：$exc"
                    }
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
            rows += WatchRow(item, kind, snap, proxySnap, warn)
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

    private fun describe(row: WatchRow, cfg: WatchConfig): Map<String, Any?> {
        val item = row.item
        val snap = row.snap ?: return linkedMapOf(
            "code" to item.code,
            "name" to item.name,
            "kind" to row.kind,
            "warn" to row.warn,
            "mas" to emptyList<Map<String, Any?>>(),
            "hit" to emptyList<String>(),
        )
        val result = evaluate(item, snap, cfg)
        val mas = itemBiasMas(item, cfg).mapNotNull { n ->
            val ma = sma(snap.closes, n) ?: return@mapNotNull null
            mapOf(
                "n" to n,
                "ma" to fmt(ma, snap.decimals),
                "bias" to round2((snap.price - ma) / ma * 100.0),
            )
        }
        val crossConfig = itemCross(item, cfg)
        val cross = if (crossConfig.enabled) {
            detectCross(snap.closes, crossConfig.short, crossConfig.long)
        } else {
            null
        }
        val crossText = if (cross == null) "" else {
            val action = if (cross.kind == Cross.GOLD) "金叉" else "死叉"
            "MA${crossConfig.short} $action MA${crossConfig.long}"
        }
        val tConfig = itemTRange(item, cfg)
        val tInfo = if (!tConfig.enabled) {
            null
        } else {
            val info = tLevels(item, snap, cfg)
            if (info == null) {
                mapOf("bad" to "基准价或昨收取不到，或两档偏离都填了 0")
            } else {
                val state = tState(info, snap.price)
                mapOf(
                    "base" to fmt(info.base, snap.decimals),
                    "src" to info.source,
                    "side" to state.side,
                    "level" to state.level,
                    "levels" to info.levels.map { level ->
                        mapOf(
                            "n" to level.n,
                            "pct" to level.pct,
                            "low" to fmt(level.low, snap.decimals),
                            "high" to fmt(level.high, snap.decimals),
                            "gap_low" to round2((snap.price - level.low) / level.low * 100.0),
                            "gap_high" to round2((level.high - snap.price) / level.high * 100.0),
                        )
                    },
                )
            }
        }
        val out = linkedMapOf<String, Any?>(
            "code" to snap.code,
            "name" to item.name.ifEmpty { snap.name },
            "kind" to snap.kind,
            "label" to snap.label,
            "price" to fmt(snap.price, snap.decimals),
            "prev" to fmt(snap.prevClose, snap.decimals),
            "change_pct" to round2(snap.changePct ?: 0.0),
            "mas" to mas,
            "cross" to crossText,
            "hit" to result.hits.map { plain(it.text) },
            "warn" to (row.warn?.takeUnless { it.startsWith("行情时间") } ?: result.error),
            "time" to snap.timeDisplay,
            "bars" to snap.bars,
            "threshold" to listOf(
                fmt(com.stockwatch.app.core.itemBiasUpperPct(item, cfg), 2),
                fmt(com.stockwatch.app.core.itemBiasLowerPct(item, cfg), 2),
            ),
            "t" to tInfo,
        )
        val proxy = row.proxySnap
        if (proxy != null) {
            val proxyResult = evaluateProxy(item, proxy, cfg)
            out["proxy"] = mapOf(
                "code" to proxy.code,
                "name" to proxy.name,
                "price" to fmt(proxy.price, proxy.decimals),
                "change_pct" to round2(proxy.changePct ?: 0.0),
                "hit" to proxyResult.hits.map { plain(it.text) },
            )
        }
        return out
    }

    private fun round2(value: Double): Double = round(value * 100.0) / 100.0

    private fun plain(value: String): String = Regex("<[^>]+>").replace(value, "").trim()

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

    private data class WatchRow(
        val item: WatchItem,
        val kind: String,
        val snap: MarketSnapshot?,
        val proxySnap: MarketSnapshot?,
        val warn: String?,
    )

    private data class PendingHit(val hit: AlertHit, val key: String)

    companion object {
        private const val TAG = "WatchEngine"
        private const val OVERVIEW_TTL_MS = 20_000L
    }
}
