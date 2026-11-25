package com.stockwatch.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.stockwatch.app.core.NotificationContent
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var notifier: Notifier
    private lateinit var configStore: ConfigStore
    private lateinit var serviceStatus: TextView
    private lateinit var notificationStatus: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var configStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notifier = Notifier(this)
        configStore = WatchRuntime.store(this)
        configStore.ensureDefault()
        setContentView(buildContent())
        if (savedInstanceState == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        root.addView(
            TextView(this).apply {
                text = "盯盘助手"
                textSize = 24f
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            },
        )
        root.addView(
            TextView(this).apply {
                text = "原生后台服务 · 仅手机本地通知 · 不依赖 PC"
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(20))
            },
        )

        root.addView(
            TextView(this).apply {
                val info = packageManager.getPackageInfo(packageName, 0)
                text = "版本 ${info.versionName} · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER}"
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(16))
            },
        )

        serviceStatus = statusRow(root, "前台服务")
        notificationStatus = statusRow(root, "通知权限")
        batteryStatus = statusRow(root, "电池优化")
        configStatus = statusRow(root, "当前配置")

        root.addButton("启动盯盘") {
            WatchService.start(this)
            Toast.makeText(this, "启动指令已发送", Toast.LENGTH_SHORT).show()
        }
        root.addButton("停止盯盘") {
            stopService(Intent(this, WatchService::class.java))
            refreshStatus()
        }
        root.addButton("打开配置面板") {
            startActivity(Intent(this, PanelActivity::class.java))
        }
        root.addButton("通知权限与测试通知") {
            if (notifier.notificationsAllowed(NotifierChannels.SYSTEM)) {
                val sent = notifier.post(
                    NotificationContent("本地通知测试", "通知通道正常，可以接收做T、乖离和涨跌幅提醒。"),
                    NotifierChannels.SYSTEM,
                    "system",
                    "system|test",
                )
                Toast.makeText(this, if (sent) "测试通知已发送" else "通知渠道不可用", Toast.LENGTH_SHORT).show()
            } else {
                openNotificationSettings()
            }
        }
        root.addButton("申请忽略电池优化") { requestBatteryExemption() }
        root.addButton("打开厂商自启动设置") { openAutostartSettings() }

        root.addView(
            TextView(this).apply {
                text = "保活建议：允许通知、关闭本应用省电限制、在厂商设置中允许自启动和后台联网。" +
                    "服务运行时会持有部分唤醒锁，以便锁屏后按配置轮询。"
                textSize = 12f
                setPadding(0, dp(12), 0, 0)
            },
        )
        return ScrollView(this).apply { addView(root) }
    }

    private fun statusRow(root: LinearLayout, label: String): TextView = TextView(this).apply {
        text = "$label：检查中"
        textSize = 14f
        setPadding(0, dp(5), 0, dp(5))
        root.addView(this)
    }

    private fun LinearLayout.addButton(label: String, onClick: View.OnClickListener): Button =
        Button(this@MainActivity).apply {
            text = label
            isAllCaps = false
            setOnClickListener(onClick)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            addView(this)
        }

    private fun refreshStatus() {
        serviceStatus.text = if (WatchService.instanceRunning) {
            "前台服务：运行中（${WatchService.statusText}）"
        } else {
            "前台服务：未运行"
        }
        notificationStatus.text = if (notifier.notificationsAllowed()) "通知权限：已允许" else "通知权限：未允许"
        val power = getSystemService(PowerManager::class.java)
        batteryStatus.text = if (power.isIgnoringBatteryOptimizations(packageName)) {
            "电池优化：已豁免"
        } else {
            "电池优化：未豁免"
        }
        configStatus.text = try {
            val cfg = configStore.load()
            "当前配置：${cfg.items.count { it.enabled }} 个启用 / ${cfg.items.size} 个标的"
        } catch (exc: Exception) {
            "当前配置：读取失败（${exc.message}）"
        }
    }

    private fun openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
            return
        }
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
        } else {
            appDetailsIntent()
        }
        if (!openSafely(intent)) openSafely(appDetailsIntent())
    }

    private fun requestBatteryExemption() {
        val power = getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "已经在电池优化白名单中", Toast.LENGTH_SHORT).show()
            return
        }
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"),
        )
        if (!openSafely(direct)) {
            openSafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openAutostartSettings() {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
        val candidates = mutableListOf<Intent>()
        when {
            "xiaomi" in manufacturer || "redmi" in manufacturer -> candidates += componentIntent(
                "com.miui.securitycenter",
                "com.miui.securitycenter.permissions.PermissionsEditorActivity",
            )
            "huawei" in manufacturer || "honor" in manufacturer -> candidates += componentIntent(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            )
            "oppo" in manufacturer || "realme" in manufacturer || "oneplus" in manufacturer -> candidates += componentIntent(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            )
            "vivo" in manufacturer || "iqoo" in manufacturer -> candidates += componentIntent(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgVivoManagementActivity",
            )
            "samsung" in manufacturer -> candidates += componentIntent(
                "com.samsung.android.lool",
                "com.samsung.android.lool.settingsui.MainActivity",
            )
        }
        for (intent in candidates) {
            if (openSafely(intent)) return
        }
        openSafely(appDetailsIntent())
    }

    private fun componentIntent(packageName: String, className: String): Intent =
        Intent().setComponent(ComponentName(packageName, className))

    private fun appDetailsIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:$packageName")
    }

    private fun openSafely(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_REQUEST) refreshStatus()
    }

    companion object {
        private const val NOTIFICATION_REQUEST = 1001
    }
}
