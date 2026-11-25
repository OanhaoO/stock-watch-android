package com.stockwatch.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.stockwatch.app.core.NotificationContent
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var notifier: Notifier
    private lateinit var configStore: ConfigStore
    private lateinit var serviceStatus: StatusViews
    private lateinit var notificationStatus: StatusViews
    private lateinit var batteryStatus: StatusViews
    private lateinit var configStatus: StatusViews
    private lateinit var heroTitle: TextView
    private lateinit var heroDetail: TextView
    private lateinit var servicePill: TextView
    private lateinit var serviceDot: View
    private lateinit var stopButton: Button
    private lateinit var batteryAction: TextView

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
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(getColor(R.color.page_background))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        val availableWidth = resources.displayMetrics.widthPixels
        val width = minOf(dp(760), (availableWidth - dp(40)).coerceAtLeast(dp(280)))
        scroll.addView(
            content,
            FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL),
        )

        content.addView(buildHeader())
        content.addView(buildHero())

        sectionTitle(content, "运行状态", "返回页面自动刷新")
        serviceStatus = statusCard(
            R.drawable.ic_power,
            R.color.success_soft,
            R.color.success,
            "前台服务",
            "未运行",
            "等待启动",
        )
        notificationStatus = statusCard(
            R.drawable.ic_bell,
            R.color.info_soft,
            R.color.accent,
            "通知权限",
            "检查中",
            "用于接收行情提醒",
        )
        statusPair(content, serviceStatus.view, notificationStatus.view)

        batteryStatus = statusCard(
            R.drawable.ic_battery,
            R.color.warning_soft,
            R.color.warning,
            "电池优化",
            "检查中",
            "建议加入白名单",
        )
        configStatus = statusCard(
            R.drawable.ic_sliders,
            R.color.accent_soft,
            R.color.accent,
            "当前配置",
            "—",
            "正在读取本机配置",
        )
        statusPair(content, batteryStatus.view, configStatus.view)
        notificationStatus.view.apply {
            isClickable = true
            isFocusable = true
            ripple()
            setOnClickListener { testNotification() }
        }
        batteryStatus.view.apply {
            isClickable = true
            isFocusable = true
            ripple()
            setOnClickListener { requestBatteryExemption() }
        }

        sectionTitle(content, "快捷操作")
        content.addView(
            actionRow(R.drawable.ic_sliders, R.color.accent_soft, R.color.accent, "配置面板", "管理盯盘标的与提醒规则") {
                startActivity(Intent(this, PanelActivity::class.java))
            },
        )
        val notificationAction = actionRow(
            R.drawable.ic_bell,
            R.color.info_soft,
            R.color.accent,
            "通知与测试",
            "检查权限并发送一条测试通知",
        ) { testNotification() }
        content.addView(
            notificationAction,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            },
        )

        sectionTitle(content, "后台保活")
        val keepAliveCard = surface().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        val batteryRow = actionRow(
            R.drawable.ic_battery,
            R.color.warning_soft,
            R.color.warning,
            "电池优化",
            "关闭系统限制，保持后台轮询",
            endLabel = "去设置",
        ) { requestBatteryExemption() }
        batteryAction = batteryRow.findViewById(R.id.home_action_end)
        keepAliveCard.addView(batteryRow)
        keepAliveCard.addView(
            View(this).apply { setBackgroundColor(getColor(R.color.outline)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)),
        )
        keepAliveCard.addView(
            actionRow(R.drawable.ic_power, R.color.accent_soft, R.color.accent, "厂商自启动", "允许自启动、关联启动和后台联网") {
                openAutostartSettings()
            },
        )
        content.addView(keepAliveCard)
        content.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(getColor(R.color.info_soft), dp(16))
                setPadding(dp(14), dp(14), dp(14), dp(14))
                addView(
                    ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_info)
                        setColorFilter(getColor(R.color.accent))
                    },
                    LinearLayout.LayoutParams(dp(20), dp(20)),
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text = "稳定提醒小贴士\n允许通知、关闭省电限制并开启自启动；锁屏后的存活率仍与机型和系统版本有关。"
                        textSize = 12f
                        setTextColor(getColor(R.color.text_secondary))
                        setLineSpacing(dp(2).toFloat(), 1f)
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(10)
                    },
                )
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(20)
            },
        )

        val info = packageManager.getPackageInfo(packageName, 0)
        content.addView(
            TextView(this).apply {
                text = "版本 ${info.versionName} · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER}\n个人自用 · 行情数据仅供参考"
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.text_tertiary))
                setLineSpacing(dp(3).toFloat(), 1f)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(18)
            },
        )
        return scroll
    }

    private fun buildHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            ImageView(this@MainActivity).apply {
                setImageResource(R.mipmap.ic_launcher)
                contentDescription = "盯盘助手图标"
            },
            LinearLayout.LayoutParams(dp(52), dp(52)),
        )
        addView(
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    TextView(this@MainActivity).apply {
                        text = "盯盘助手"
                        textSize = 22f
                        setTextColor(getColor(R.color.text_primary))
                        typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    },
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text = "行情监控 · 智能提醒"
                        textSize = 12f
                        setTextColor(getColor(R.color.text_secondary))
                    },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(2)
                    },
                )
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        val info = packageManager.getPackageInfo(packageName, 0)
        addView(
            TextView(this@MainActivity).apply {
                text = "v${info.versionName}"
                textSize = 11f
                setTextColor(getColor(R.color.accent))
                background = rounded(getColor(R.color.accent_soft), dp(12))
                setPadding(dp(10), dp(6), dp(10), dp(6))
            },
        )
    }

    private fun buildHero(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(getColor(R.color.hero_start), getColor(R.color.hero_end)),
        ).apply { cornerRadius = dp(24).toFloat() }
        setPadding(dp(20), dp(18), dp(20), dp(18))

        addView(
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                serviceDot = View(this@MainActivity).apply {
                    background = circle(0xFFF1C074.toInt())
                }
                addView(serviceDot, LinearLayout.LayoutParams(dp(8), dp(8)))
                addView(
                    TextView(this@MainActivity).apply {
                        text = "后台盯盘服务"
                        textSize = 12f
                        setTextColor(getColor(R.color.hero_secondary))
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(8)
                    },
                )
                servicePill = TextView(this@MainActivity).apply {
                    text = "未运行"
                    textSize = 11f
                    setTextColor(getColor(R.color.hero_text))
                    background = rounded(0x22FFFFFF, dp(12))
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                }
                addView(servicePill)
            },
        )
        heroTitle = TextView(this@MainActivity).apply {
            text = "开启全天候盯盘"
            textSize = 22f
            setTextColor(getColor(R.color.hero_text))
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        addView(
            heroTitle,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            },
        )
        heroDetail = TextView(this@MainActivity).apply {
            text = "按你的配置自动检查行情，把重要变化直接送到手机通知栏。"
            textSize = 13f
            setTextColor(getColor(R.color.hero_secondary))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        addView(
            heroDetail,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            },
        )

        addView(
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                val start = heroButton("启动盯盘", R.drawable.ic_play, primary = true).apply {
                    setOnClickListener {
                        WatchService.start(this@MainActivity)
                        Toast.makeText(this@MainActivity, "启动指令已发送", Toast.LENGTH_SHORT).show()
                        refreshStatus()
                        window.decorView.postDelayed({ refreshStatus() }, 400)
                    }
                }
                stopButton = heroButton("停止盯盘", R.drawable.ic_stop, primary = false).apply {
                    setOnClickListener {
                        stopService(Intent(this@MainActivity, WatchService::class.java))
                        refreshStatus()
                        window.decorView.postDelayed({ refreshStatus() }, 400)
                    }
                }
                addView(start, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(stopButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(10) })
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
                topMargin = dp(18)
            },
        )
        addView(
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_shield)
                        setColorFilter(0xB3D5E7E3.toInt())
                    },
                    LinearLayout.LayoutParams(dp(15), dp(15)),
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text = "配置保存在本机 · 提醒不依赖电脑"
                        textSize = 11f
                        setTextColor(getColor(R.color.hero_secondary))
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(6)
                    },
                )
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14)
            },
        )
    }

    private fun heroButton(label: String, icon: Int, primary: Boolean): Button = Button(this).apply {
        text = label
        isAllCaps = false
        isEnabled = true
        minHeight = 0
        minWidth = 0
        stateListAnimator = null
        textSize = 14f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(10), 0, dp(10), 0)
        val fill = if (primary) 0xFF72E3CB.toInt() else 0x18FFFFFF
        val stroke = if (primary) null else 0x55FFFFFF
        this.setBackgroundDrawable(
            RippleDrawable(
                ColorStateList.valueOf(if (primary) 0x33000000 else 0x22FFFFFF),
                rounded(fill, dp(14), stroke),
                null,
            ),
        )
        setTextColor(if (primary) 0xFF073B3A.toInt() else getColor(R.color.hero_text))
        val drawable = getDrawable(icon)?.mutate()?.apply {
            setTint(if (primary) 0xFF073B3A.toInt() else getColor(R.color.hero_text))
        }
        setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null)
        compoundDrawablePadding = dp(7)
    }

    private fun sectionTitle(root: LinearLayout, title: String, caption: String = "") {
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    TextView(this@MainActivity).apply {
                        text = title
                        textSize = 15f
                        setTextColor(getColor(R.color.text_primary))
                        typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                if (caption.isNotEmpty()) {
                    addView(
                        TextView(this@MainActivity).apply {
                            text = caption
                            textSize = 10f
                            setTextColor(getColor(R.color.text_tertiary))
                            gravity = Gravity.END
                        },
                    )
                }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(22)
            },
        )
    }

    private fun statusPair(root: LinearLayout, first: View, second: View) {
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(first, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) })
                addView(second, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(5) })
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            },
        )
    }

    private fun statusCard(icon: Int, iconBackground: Int, iconTint: Int, title: String, value: String, detail: String): StatusViews {
        val card = surface().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            addView(
                ImageView(this@MainActivity).apply {
                    setImageResource(icon)
                    setColorFilter(getColor(iconTint))
                    background = circle(getColor(iconBackground))
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                },
                LinearLayout.LayoutParams(dp(36), dp(36)),
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = title
                    textSize = 11f
                    setTextColor(getColor(R.color.text_secondary))
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(10)
                },
            )
        }
        val valueView = TextView(this).apply {
            text = value
            textSize = 18f
            setTextColor(getColor(R.color.text_primary))
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            maxLines = 1
        }
        card.addView(
            valueView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(3)
            },
        )
        val detailView = TextView(this).apply {
            text = detail
            textSize = 10f
            setTextColor(getColor(R.color.text_tertiary))
            maxLines = 2
        }
        card.addView(
            detailView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            },
        )
        return StatusViews(card, valueView, detailView)
    }

    private fun actionRow(
        icon: Int,
        iconBackground: Int,
        iconTint: Int,
        title: String,
        subtitle: String,
        endLabel: String = "",
        onClick: View.OnClickListener,
    ): LinearLayout = surface().apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        isClickable = true
        isFocusable = true
        ripple()
        setOnClickListener(onClick)
        addView(
            ImageView(this@MainActivity).apply {
                setImageResource(icon)
                setColorFilter(getColor(iconTint))
                background = circle(getColor(iconBackground))
                setPadding(dp(9), dp(9), dp(9), dp(9))
            },
            LinearLayout.LayoutParams(dp(40), dp(40)),
        )
        addView(
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    TextView(this@MainActivity).apply {
                        text = title
                        textSize = 14f
                        setTextColor(getColor(R.color.text_primary))
                        typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    },
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text = subtitle
                        textSize = 11f
                        setTextColor(getColor(R.color.text_secondary))
                        maxLines = 2
                    },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(3)
                    },
                )
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) },
        )
        if (endLabel.isNotEmpty()) {
            addView(
                TextView(this@MainActivity).apply {
                    id = R.id.home_action_end
                    text = endLabel
                    textSize = 11f
                    setTextColor(getColor(R.color.accent))
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(8)
                },
            )
        } else {
            addView(
                ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_arrow)
                    setColorFilter(getColor(R.color.text_tertiary))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(8) },
            )
        }
    }

    private fun surface(): LinearLayout = LinearLayout(this).apply {
        this.setBackgroundDrawable(rounded(getColor(R.color.surface), dp(18), getColor(R.color.outline)))
        elevation = dp(1).toFloat()
    }

    private fun ripple() {
        val content = rounded(getColor(R.color.surface), dp(18), getColor(R.color.outline))
        this.setBackgroundDrawable(
            RippleDrawable(
                ColorStateList.valueOf(getColor(R.color.ripple)),
                content,
                rounded(0x22000000.toInt(), dp(18)),
            ),
        )
    }

    private fun rounded(color: Int, radius: Int, strokeColor: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        strokeColor?.let { setStroke(dp(1), it) }
    }

    private fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun refreshStatus() {
        val running = WatchService.instanceRunning
        serviceStatus.value.text = if (running) "运行中" else "未运行"
        serviceStatus.detail.text = if (running) WatchService.statusText else "等待启动"
        heroTitle.text = if (running) "盯盘服务运行中" else "开启全天候盯盘"
        heroDetail.text = if (running) {
            "服务正在后台工作。关闭此页面不会停止盯盘，重要变化会通过本地通知提醒。"
        } else {
            "按你的配置自动检查行情，把重要变化直接送到手机通知栏。"
        }
        servicePill.text = if (running) "运行中" else "未运行"
        serviceDot.background = circle(if (running) 0xFF78ECD1.toInt() else 0xFFF1C074.toInt())
        stopButton.isEnabled = running
        stopButton.alpha = if (running) 1f else 0.5f

        val notificationsAllowed = notifier.notificationsAllowed()
        notificationStatus.value.text = if (notificationsAllowed) "已允许" else "未允许"
        notificationStatus.detail.text = if (notificationsAllowed) "本地通知可用" else "点击检查通知设置"

        val power = getSystemService(PowerManager::class.java)
        val batteryExempt = power.isIgnoringBatteryOptimizations(packageName)
        batteryStatus.value.text = if (batteryExempt) "已豁免" else "未豁免"
        batteryStatus.detail.text = if (batteryExempt) "后台限制已关闭" else "建议加入白名单"
        batteryAction.text = if (batteryExempt) "已豁免" else "去设置"

        configStatus.value.text = try {
            val cfg = configStore.load()
            "${cfg.items.count { it.enabled }} / ${cfg.items.size}"
        } catch (_: Exception) {
            "读取失败"
        }
        configStatus.detail.text = try {
            val cfg = configStore.load()
            when {
                cfg.items.isEmpty() -> "尚未配置盯盘标的"
                cfg.items.none { it.enabled } -> "所有标的均已停用"
                else -> "个标的正在盯盘"
            }
        } catch (exc: Exception) {
            exc.message ?: "请打开面板重新保存"
        }
    }

    private fun testNotification() {
        if (!notifier.notificationsAllowed(NotifierChannels.SYSTEM)) {
            openNotificationSettings()
            return
        }
        val sent = notifier.post(
            NotificationContent("本地通知测试", "通知通道正常，可以接收做T、乖离和涨跌幅提醒。"),
            NotifierChannels.SYSTEM,
            "system",
            "system|test",
        )
        Toast.makeText(this, if (sent) "测试通知已发送" else "通知渠道不可用", Toast.LENGTH_SHORT).show()
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

    private data class StatusViews(val view: View, val value: TextView, val detail: TextView)

    companion object {
        private const val NOTIFICATION_REQUEST = 1001
    }
}
