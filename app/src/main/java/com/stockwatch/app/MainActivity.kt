package com.stockwatch.app

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = TextView(this).apply {
            setPadding(48, 60, 48, 48)
            textSize = 15f
            text = info()
        }
        setContentView(ScrollView(this).apply { addView(label) })
    }

    private fun info(): String {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
        return buildString {
            appendLine("盯盘助手 $version")
            appendLine()
            appendLine("Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            append("骨架包，说明 Actions 出的 APK 能装上。行情、做T 档位与本地通知在后续提交里加。")
        }
    }
}
