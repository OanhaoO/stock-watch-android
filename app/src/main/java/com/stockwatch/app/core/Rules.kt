package com.stockwatch.app.core

import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs

data class MarketSnapshot(
    val name: String,
    val code: String,
    val kind: String,
    val price: Double,
    val prevClose: Double?,
    val changePct: Double?,
    val date: String,
    val timeDisplay: String,
    val closes: List<Double>,
    val bars: Int = closes.size,
    val label: String = if (kind == FUND) "净值" else "现价",
    val high: Double? = null,
    val low: Double? = null,
    val decimals: Int? = null,
)

data class AlertHit(val rule: String, val scope: String, val text: String)

data class RuleEvaluation(
    val hits: List<AlertHit> = emptyList(),
    val detail: String? = null,
    val error: String? = null,
)

data class TLevel(val n: Int, val pct: Double, val low: Double, val high: Double)

data class TInfo(val base: Double, val source: String, val levels: List<TLevel>)

data class TState(val side: String, val level: Int) {
    val key: String get() = if (side == TSide.IN) TSide.IN else "$side$level"

    companion object {
        val IN = TState(TSide.IN, 0)
    }
}

object TSide {
    const val IN = "in"
    const val BELOW = "below"
    const val ABOVE = "above"
}

data class NotificationContent(val title: String, val body: String)

data class TZoneAction(
    val key: String,
    val day: String,
    val zone: String,
    val notification: NotificationContent?,
    val rearmed: Boolean,
)

fun tLevels(item: WatchItem, snap: MarketSnapshot, cfg: WatchConfig): TInfo? {
    val config = itemTRange(item, cfg)
    if (!config.enabled) return null
    var base = positivePrice(config.base)
    val source = if (base != null) {
        "手填基准"
    } else {
        base = snap.prevClose?.let(::positivePrice)
        if (snap.kind == LISTED) "昨收" else "前净值"
    }
    val safeBase = base ?: return null
    val levels = mutableListOf<TLevel>()
    for ((n, pct) in listOf(1 to config.dev1Pct, 2 to config.dev2Pct)) {
        if (pct > 0.0) {
            levels += TLevel(
                n = n,
                pct = pct,
                low = safeBase * (1.0 - pct / 100.0),
                high = safeBase * (1.0 + pct / 100.0),
            )
        }
    }
    return if (levels.isEmpty()) null else TInfo(safeBase, source, levels)
}

fun tState(info: TInfo, price: Double): TState {
    var state = TState.IN
    for (level in info.levels) {
        state = when {
            price <= level.low -> TState(TSide.BELOW, level.n)
            price >= level.high -> TState(TSide.ABOVE, level.n)
            else -> state
        }
    }
    return state
}

fun tText(snap: MarketSnapshot, info: TInfo, state: TState): String? {
    if (state.side == TSide.IN) return null
    val level = info.levels.firstOrNull { it.n == state.level } ?: return null
    val edge = if (state.side == TSide.BELOW) level.low else level.high
    val action = if (state.side == TSide.BELOW) "可低吸" else "可高抛"
    val band = if (info.levels.size > 1) "第${state.level}档" else "偏离"
    val direction = if (state.side == TSide.BELOW) "下" else "上"
    val pct = if (state.side == TSide.BELOW) -level.pct else level.pct
    return "做T：现价 ${fmt(snap.price, snap.decimals)} 破做T${band}${direction}沿 " +
        "${fmt(edge, snap.decimals)}（${signed(pct)}%），$action｜基准 ${fmt(info.base, snap.decimals)}（${info.source}）"
}

fun evaluate(item: WatchItem, snap: MarketSnapshot, cfg: WatchConfig): RuleEvaluation {
    val hits = mutableListOf<AlertHit>()
    val info = tLevels(item, snap, cfg)
    if (info != null) {
        val state = tState(info, snap.price)
        val text = tText(snap, info, state)
        if (text != null) hits += AlertHit("t_${state.key}", "档${state.level}", text)
    }

    if (snap.closes.size < itemMinHistoryBars(item, cfg)) {
        return RuleEvaluation(
            hits = hits,
            error = "历史数据不足（${snap.closes.size} 根，需 ${itemMinHistoryBars(item, cfg)} 根）",
        )
    }

    val lines = mutableListOf<String>()
    for (n in itemBiasMas(item, cfg)) {
        val ma = sma(snap.closes, n) ?: continue
        val bias = (snap.price - ma) / ma * 100.0
        lines += "MA$n ${fmt(ma, snap.decimals)} | 乖离 ${signed(bias)}%"
        if (!cfg.biasEnabled) continue
        val upper = itemBiasUpperPct(item, cfg)
        val lower = itemBiasLowerPct(item, cfg)
        when {
            bias >= upper -> hits += AlertHit(
                "bias_up",
                "MA$n",
                "${snap.label}高于 MA$n ${signed(bias)}%，超过 +${fixed(upper)}%",
            )
            bias <= lower -> hits += AlertHit(
                "bias_dn",
                "MA$n",
                "${snap.label}低于 MA$n ${signed(bias)}%，超过 ${fixed(lower)}%",
            )
        }
    }

    val change = itemChange(item, cfg)
    val pct = snap.changePct
    if (change.enabled && pct != null) {
        when {
            pct >= change.upperPct -> hits += AlertHit(
                "change",
                "day",
                "当日 ${signed(pct)}%，超过 +${fixed(change.upperPct)}%",
            )
            pct <= change.lowerPct -> hits += AlertHit(
                "change",
                "day",
                "当日 ${signed(pct)}%，超过 ${fixed(change.lowerPct)}%",
            )
        }
    }

    val cross = itemCross(item, cfg)
    if (cross.enabled) {
        val detected = detectCross(snap.closes, cross.short, cross.long)
        if (detected != null) {
            val action = if (detected.kind == Cross.GOLD) "金叉" else "死叉"
            hits += AlertHit(
                "cross_${detected.kind}",
                "cross",
                "MA${cross.short} $action MA${cross.long}（${fmt(detected.shortMa, snap.decimals)} / " +
                    "${fmt(detected.longMa, snap.decimals)}）",
            )
        }
    }
    return RuleEvaluation(hits = hits, detail = lines.takeIf { it.isNotEmpty() }?.joinToString("；"))
}

fun evaluateProxy(item: WatchItem, proxySnap: MarketSnapshot, cfg: WatchConfig): RuleEvaluation {
    val config = proxyConfig(item, cfg)
    val hits = mutableListOf<AlertHit>()
    val lines = mutableListOf<String>()
    for (n in config.biasMas) {
        val ma = sma(proxySnap.closes, n) ?: continue
        val bias = (proxySnap.price - ma) / ma * 100.0
        lines += "MA$n ${fmt(ma)} | 乖离 ${signed(bias)}%"
        when {
            bias >= config.upperPct -> hits += AlertHit(
                "proxy_up",
                "MA$n",
                "代理现价高于 MA$n ${signed(bias)}%（阈值 +${fixed(config.upperPct)}%）",
            )
            bias <= config.lowerPct -> hits += AlertHit(
                "proxy_dn",
                "MA$n",
                "代理现价低于 MA$n ${signed(bias)}%（阈值 ${fixed(config.lowerPct)}%）",
            )
        }
    }
    val pct = proxySnap.changePct
    if (pct != null && abs(pct) >= config.upperPct) {
        hits += AlertHit(
            "proxy_change",
            "day",
            "代理当日 ${signed(pct)}%（阈值 ±${fixed(config.upperPct)}%）",
        )
    }
    return RuleEvaluation(hits = hits, detail = lines.takeIf { it.isNotEmpty() }?.joinToString("；"))
}

fun cooldownMinutesFor(item: WatchItem, cfg: WatchConfig, rule: String, kind: String): Long? {
    if (kind == FUND || rule.startsWith("cross") || rule.startsWith("proxy")) return null
    if (rule == "change") return itemChange(item, cfg).cooldownMinutes
    return itemBiasCooldownMinutes(item, cfg)
}

fun buildNotification(
    snap: MarketSnapshot,
    detail: String?,
    bodyLines: List<String>,
    evaluatedAt: LocalDateTime,
    tag: String = "",
): NotificationContent {
    val lines = mutableListOf<String>()
    val pct = snap.changePct ?: 0.0
    val previous = snap.prevClose?.let { "  前值 ${fmt(it, snap.decimals)}" }.orEmpty()
    lines += "${snap.label} ${fmt(snap.price, snap.decimals)} (${signed(pct)}%)$previous"
    if (!detail.isNullOrEmpty()) lines += detail
    lines += bodyLines
    lines += "${snap.timeDisplay.ifEmpty { "-" }} ｜ 判定 ${evaluatedAt.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))}"
    return NotificationContent(title = "${snap.name} ${snap.code}$tag", body = lines.joinToString("\n"))
}

fun planTZone(
    snap: MarketSnapshot,
    info: TInfo,
    state: AlertState,
    day: String,
    evaluatedAt: LocalDateTime,
    tag: String = "",
): TZoneAction? {
    val key = "${snap.code}|zone"
    val current = tState(info, snap.price)
    if (current.key == state.zone(key, day)) return null
    if (current.side == TSide.IN) {
        state.setZone(key, day, current.key)
        return TZoneAction(key, day, current.key, null, rearmed = true)
    }
    val text = tText(snap, info, current) ?: return null
    return TZoneAction(
        key = key,
        day = day,
        zone = current.key,
        notification = buildNotification(snap, null, listOf(text), evaluatedAt, tag),
        rearmed = false,
    )
}

fun confirmTZone(state: AlertState, action: TZoneAction) {
    if (!action.rearmed) state.setZone(action.key, action.day, action.zone)
}

private fun positivePrice(value: Double): Double? = value.takeIf { it.isFinite() && it > 0.0 }

private fun signed(value: Double): String = fixed(value, signed = true)

private fun fixed(value: Double, signed: Boolean = false): String =
    String.format(Locale.US, "%${if (signed) "+" else ""}.2f", value)

private fun fmt(value: Double?, digits: Int? = null): String {
    if (value == null) return "-"
    val places = digits ?: if (abs(value) < 10.0) 3 else 2
    return String.format(Locale.US, "%.${places}f", value)
}
