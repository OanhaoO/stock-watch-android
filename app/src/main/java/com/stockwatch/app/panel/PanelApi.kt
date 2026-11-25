package com.stockwatch.app.panel

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.stockwatch.app.Notifier
import com.stockwatch.app.NotifierChannels
import com.stockwatch.app.WatchRuntime
import com.stockwatch.app.WatchService
import com.stockwatch.app.core.FUND
import com.stockwatch.app.core.LISTED
import com.stockwatch.app.core.MarketTime
import com.stockwatch.app.core.NotificationContent
import com.stockwatch.app.core.defaultWatchConfigMap
import com.stockwatch.app.core.asObject
import com.stockwatch.app.core.asString
import com.stockwatch.app.core.fetchQuotes
import com.stockwatch.app.core.fmt
import com.stockwatch.app.core.fundName
import com.stockwatch.app.core.inSession
import com.stockwatch.app.core.listedKind
import com.stockwatch.app.core.normalizeSymbol
import com.stockwatch.app.core.parseJson
import com.stockwatch.app.core.searchCode
import com.stockwatch.app.core.secondsToNextSession
import com.stockwatch.app.core.stringifyJson
import com.stockwatch.app.core.watchConfigMap
import java.net.URLDecoder
import java.time.Instant
import java.time.format.DateTimeFormatter

class PanelApi(private val originalContext: Context) : PanelHandler {
    private val context = originalContext.applicationContext
    private val notifier = Notifier(context)
    private val configStore = WatchRuntime.store(context)
    private val engine get() = WatchRuntime.engine(context)

    override fun handle(request: PanelRequest): PanelResponse = try {
        when {
            request.method == "GET" && path(request.target) == "/api/state" -> state(request.target)
            request.method == "POST" && path(request.target) == "/api/config" -> saveConfig(request.body)
            request.method == "POST" && path(request.target) == "/api/watch" -> watch(request.body)
            request.method == "POST" && path(request.target) in setOf("/api/test-notify", "/api/test-push") ->
                testNotification()
            request.method == "GET" && path(request.target) == "/api/resolve" -> resolve(request.target)
            path(request.target).startsWith("/api/") -> PanelResponse.error(405, "不支持的请求方法")
            else -> PanelResponse.error(404, "接口不存在")
        }
    } catch (exc: Exception) {
        Log.e(TAG, "面板接口异常", exc)
        PanelResponse.error(500, exc.message ?: "面板服务异常")
    }

    private fun state(target: String): PanelResponse {
        val cfg = configStore.load()
        val now = MarketTime.now()
        val active = cfg.items.count { it.enabled }
        val trading = inSession(now, cfg.sessions)
        val watcher = WatchRuntime.status().toMutableMap().apply {
            put("running", WatchService.instanceRunning)
            put(
                "mode",
                if (!WatchService.instanceRunning) "未运行" else WatchService.statusText,
            )
            put("in_session", trading)
            put("now", now.format(FULL_DATE_TIME_FORMAT))
            put("next_session", durationText(secondsToNextSession(cfg.sessions, now)))
            put("active_count", active)
        }
        val payload = mapOf(
            "config" to watchConfigMap(cfg),
            "defaults" to defaultWatchConfigMap(),
            "overview" to engine.overview(cfg, force = query(target, "fresh") == "1"),
            "alerts" to engine.recentAlerts().map(::alertRow),
            "watcher" to watcher,
            "notify_ready" to notificationsReady(),
            "notifications_allowed" to notificationsReady(),
            "battery_exempt" to batteryExempt(),
            "service_running" to WatchService.instanceRunning,
        )
        return PanelResponse.json(200, stringifyJson(payload))
    }

    private fun saveConfig(body: String): PanelResponse {
        val raw = try {
            parseJson(body.ifBlank { "{}" })
        } catch (exc: Exception) {
            return PanelResponse.json(
                400,
                stringifyJson(mapOf("ok" to false, "errors" to listOf("请求体解析失败：${exc.message}"))),
            )
        }
        val previous = try {
            parseJson(configStore.readRaw()).asObject()?.fields.orEmpty()
        } catch (_: Exception) {
            emptyMap()
        }
        val result = PanelConfigValidator.validate(raw, previous)
        if (result.errors.isNotEmpty()) {
            return PanelResponse.json(
                400,
                stringifyJson(mapOf("ok" to false, "errors" to result.errors)),
            )
        }
        val cfg = configStore.save(stringifyJson(result.config))
        engine.invalidateOverview()
        if (WatchService.instanceRunning) WatchService.wake(context)
        return PanelResponse.json(
            200,
            stringifyJson(
                mapOf(
                    "ok" to true,
                    "count" to cfg.items.count { it.enabled },
                    "total" to cfg.items.size,
                    "config" to watchConfigMap(cfg),
                ),
            ),
        )
    }

    private fun watch(body: String): PanelResponse {
        val action = try {
            parseJson(body.ifBlank { "{}" }).asObject()?.fields?.get("action")?.asString()
        } catch (_: Exception) {
            null
        }
        return when (action) {
            "start" -> {
                WatchService.start(context)
                PanelResponse.json(200, stringifyJson(mapOf("ok" to true)))
            }
            "stop" -> {
                context.stopService(Intent(context, WatchService::class.java))
                PanelResponse.json(200, stringifyJson(mapOf("ok" to true)))
            }
            else -> PanelResponse.json(
                400,
                stringifyJson(mapOf("ok" to false, "errors" to listOf("action 必须是 start 或 stop"))),
            )
        }
    }

    private fun testNotification(): PanelResponse {
        val content = NotificationContent(
            title = "本地通知测试",
            body = "通知通道正常，可以接收做T、乖离、交叉和涨跌幅提醒。\n${MarketTime.now().format(FULL_DATE_TIME_FORMAT)}",
        )
        val sent = notifier.post(
            content,
            NotifierChannels.T_RANGE,
            "system",
            "system|test",
        )
        return PanelResponse.json(
            200,
            stringifyJson(
                if (sent) {
                    mapOf("ok" to true)
                } else {
                    mapOf("ok" to false, "errors" to listOf("通知权限或「做T提醒」通道不可用"))
                },
            ),
        )
    }

    private fun resolve(target: String): PanelResponse {
        val code = query(target, "code").trim()
        if (code.isEmpty()) return PanelResponse.json(400, stringifyJson(mapOf("ok" to false, "errors" to listOf("先填代码"))))
        val symbol = try {
            normalizeSymbol(code)
        } catch (_: IllegalArgumentException) {
            return PanelResponse.json(400, stringifyJson(mapOf("ok" to false, "errors" to listOf("代码格式不对"))))
        }
        val number = symbol.substring(2)
        val quote = try {
            fetchQuotes(listOf(symbol))[symbol]
        } catch (_: Exception) {
            null
        }
        if (quote != null) {
            return PanelResponse.json(
                200,
                stringifyJson(
                    mapOf(
                        "ok" to true,
                        "kind" to LISTED,
                        "name" to quote.name,
                        "type_suggest" to "listed",
                        "market" to listedKind(symbol),
                        "price" to fmt(quote.price),
                    ),
                ),
            )
        }
        val info = try {
            fundName(number)
        } catch (_: Exception) {
            null
        }
        if (info != null) {
            return PanelResponse.json(
                200,
                stringifyJson(
                    mapOf(
                        "ok" to true,
                        "kind" to FUND,
                        "name" to info.name,
                        "type_suggest" to "fund",
                        "market" to info.ftype,
                        "price" to fmt(info.nav, 4),
                    ),
                ),
            )
        }
        val suggestions = searchCode(number).map { "${it.code} ${it.name}" }
        return PanelResponse.json(
            404,
            stringifyJson(
                mapOf(
                    "ok" to false,
                    "errors" to listOf("场内场外都查不到该代码"),
                    "suggest" to suggestions,
                ),
            ),
        )
    }

    private fun notificationsReady(): Boolean = listOf(
        NotifierChannels.T_RANGE,
        NotifierChannels.BIAS,
        NotifierChannels.CHANGE,
    ).all(notifier::notificationsAllowed)

    private fun batteryExempt(): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    private fun alertRow(entry: com.stockwatch.app.core.AlertEntry): Map<String, Any?> {
        val parts = entry.key.split('|', limit = 3)
        val rule = parts.getOrNull(1).orEmpty()
        return mapOf(
            "code" to parts.getOrNull(0).orEmpty(),
            "rule" to (RULE_LABELS[rule] ?: rule),
            "scope" to parts.getOrNull(2).orEmpty(),
            "day" to entry.day,
            "at" to DATE_TIME_FORMAT.format(
                Instant.ofEpochMilli(entry.ts).atZone(MarketTime.SHANGHAI),
            ),
        )
    }

    private fun path(target: String): String = target.substringBefore('?')

    private fun query(target: String, key: String): String {
        val query = target.substringAfter('?', "")
        val prefix = "$key="
        return query.split('&').firstOrNull { it.startsWith(prefix) }
            ?.substring(prefix.length)
            ?.let { URLDecoder.decode(it, "UTF-8") }
            .orEmpty()
    }

    private fun durationText(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        return "${safe / 3_600} 小时 ${safe % 3_600 / 60} 分"
    }

    companion object {
        private const val TAG = "PanelApi"
        private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
        private val FULL_DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val RULE_LABELS = mapOf(
            "bias_up" to "乖离超上限",
            "bias_dn" to "乖离超下限",
            "change" to "当日涨跌幅",
            "cross_gold" to "金叉",
            "cross_death" to "死叉",
            "t_below" to "做T低吸",
            "t_above" to "做T高抛",
            "proxy_up" to "代理超上限",
            "proxy_dn" to "代理超下限",
            "proxy_change" to "代理涨跌幅",
        )
    }
}
