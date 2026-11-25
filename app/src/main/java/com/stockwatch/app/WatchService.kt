package com.stockwatch.app

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.stockwatch.app.core.MarketTime
import com.stockwatch.app.core.NotificationContent
import com.stockwatch.app.core.inSession
import com.stockwatch.app.core.secondsToNextSession
import java.io.File

class WatchService : Service() {
    private lateinit var notifier: Notifier
    private lateinit var configStore: ConfigStore
    private lateinit var engine: WatchEngine
    private var worker: Thread? = null
    private val lock = Object()
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastErrorAt = 0L

    @Volatile
    private var running = false

    override fun onCreate() {
        super.onCreate()
        notifier = Notifier(this)
        configStore = ConfigStore(File(filesDir, "config.json"))
        configStore.ensureDefault()
        engine = WatchEngine(filesDir, notifier)
        startForegroundStatus("正在启动盯盘服务")
        acquireWakeLock()
        running = true
        instanceRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker?.isAlive != true) {
            worker = Thread({ loop() }, "stock-watch-loop").also { it.start() }
        }
        wake()
        return START_STICKY
    }

    private fun loop() {
        while (running) {
            try {
                val cfg = configStore.load()
                if (cfg.items.isEmpty()) {
                    setStatus("未配置标的，请先在面板添加")
                    waitMillis(60_000)
                    continue
                }
                val now = MarketTime.now()
                if (!inSession(now, cfg.sessions)) {
                    val seconds = secondsToNextSession(sessions = cfg.sessions, now = now)
                    setStatus("非交易时段，等待下次开盘（${cfg.items.size} 个标的）")
                    waitMillis(seconds * 1_000)
                    continue
                }
                setStatus("盯盘中 · ${cfg.items.size} 个标的 · 每 ${cfg.pollSeconds} 秒")
                val pushed = engine.runCycle(cfg)
                setStatus(
                    "盯盘中 · ${cfg.items.size} 个标的" +
                        if (pushed > 0) " · 本轮提醒 $pushed 条" else "",
                )
                waitMillis(cfg.pollSeconds.coerceAtLeast(5L) * 1_000)
            } catch (exc: Exception) {
                Log.e(TAG, "本轮异常", exc)
                setStatus("运行异常，稍后重试")
                reportError(exc)
                waitMillis(30_000)
            }
        }
    }

    private fun reportError(exc: Exception) {
        val now = System.currentTimeMillis()
        if (now - lastErrorAt < 30 * 60_000L) return
        lastErrorAt = now
        notifier.post(
            NotificationContent(
                title = "盯盘助手运行异常",
                body = "${exc.javaClass.simpleName}: ${exc.message ?: "未知错误"}",
            ),
            NotifierChannels.SYSTEM,
            "system",
            "system|error",
        )
    }

    private fun setStatus(text: String) {
        statusText = text
        notifier.updateStatus(text)
    }

    private fun waitMillis(millis: Long) {
        if (millis <= 0L || !running) return
        synchronized(lock) {
            if (running) lock.wait(millis)
        }
    }

    private fun wake() {
        synchronized(lock) { lock.notifyAll() }
    }

    private fun acquireWakeLock() {
        val manager = getSystemService(PowerManager::class.java)
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startForegroundStatus(text: String) {
        val notification = notifier.statusNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(STATUS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(STATUS_ID, notification)
        }
    }

    override fun onDestroy() {
        running = false
        instanceRunning = false
        worker?.interrupt()
        wake()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "WatchService"
        private const val WAKE_LOCK_TAG = "stock-watch:monitor"
        private const val STATUS_ID = Notifier.STATUS_ID
        const val ACTION_WAKE = "com.stockwatch.app.action.WAKE"
        const val ACTION_STOP = "com.stockwatch.app.action.STOP"

        @Volatile
        var instanceRunning = false
            private set

        @Volatile
        var statusText = "未运行"
            private set

        fun start(context: android.content.Context) {
            context.startForegroundService(Intent(context, WatchService::class.java))
        }

        fun wake(context: android.content.Context) {
            context.startForegroundService(
                Intent(context, WatchService::class.java).apply { action = ACTION_WAKE },
            )
        }
    }
}
