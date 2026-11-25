# 盯盘助手 · 安卓版移植说明

把 PC 版（`F:\千问\stock-watch`）的盯盘逻辑搬到安卓手机上独立运行：**原生 Kotlin + 前台服务常驻 + 本地通知**，不再依赖企业微信，也不依赖电脑开机。

本仓库与 PC 版分开存放，PC 版继续可用；行情口径、做T 档位、去重规则都以 PC 版为准逐条移植，避免两边行为漂移。

> 安全提醒：当前 GitHub 仓库为公开状态，签名密钥文件与口令已经随历史提交暴露。现有 APK 仅限本机验证，正式交付前必须把仓库改为私有，或轮换签名密钥并迁移到 GitHub Secrets。

## 当前进度

- [x] Gradle 工程骨架（AGP 8.4.2 / Kotlin 1.9.24 / Gradle 8.7 / JDK 17）
- [x] GitHub Actions 出包流水线
- [x] 行情与均线内核（`core`）
- [x] 规则引擎与去重（乖离 / 交叉 / 涨跌幅 / 代理 / 做T 两档）
- [x] 前台服务、本地通知、开机自启、保活自检
- [x] 内嵌本地面板、WebView 入口、共享行情引擎与提醒状态
- [x] 最新提交的 Actions 全绿（0.2.0，单元测试与签名 APK 均成功）
- [ ] 真机安装、锁屏后台运行和实际提醒验收

## 技术取向

- **零 AndroidX、零第三方库**：只用 framework API 和 `HttpURLConnection`，Gradle 构建只需 Android SDK，CI 首次通过率最高，也延续 PC 版「零依赖」的做法。测试期唯一例外是 `testImplementation junit`，不进包。
- **单模块 `app`**，但 `com.stockwatch.app.core` 包内不允许出现任何 `android.*` 依赖，保证规则引擎能在 JVM 上直接跑单元测试。
- minSdk 26 / targetSdk 34。

## 一、获取和安装 APK

仓库和 Actions 已经建立，不需要再重复初始化。

1. 登录有仓库访问权限的 GitHub 账号，打开：
   https://github.com/OanhaoO/stock-watch-android/actions
2. 进入最新的 `build-apk` 运行记录，确认「单元测试」和「编译签名 release APK」两步都成功。
3. 在页面底部 **Artifacts** 下载 `stock-watch-release-apk`，解压得到 `app-release.apk`。
   如果 GitHub 提示登录，请使用有该仓库访问权限的账号登录后再下载。
4. 把 APK 传到手机，点击安装；Android 会要求允许文件管理器或浏览器「安装未知应用」，只对当前来源临时允许即可。
5. 安装后打开 App，首页应显示 App 版本号、Android 版本和机型。看到这三项说明 APK 已正常启动。

后续每次推送 `main` 都会自动构建。签名不变时可直接覆盖安装，配置和提醒记录会保留；如果轮换过签名密钥，则不能覆盖安装，必须先备份或确认可以清除数据后卸载旧版。

本机没有 JDK、Android SDK 和 `gh` CLI，因此当前构建验证依赖 GitHub Actions。Actions 中执行的核心命令是：

```bash
gradle --no-daemon test
gradle --no-daemon assembleRelease
```

## 二、签名与仓库安全

`keystore.p12` 使用 PKCS12，别名 `stockwatch`，由 AGP 读取后给 release APK 签名。相同签名保证后续版本可以覆盖安装。

当前仓库是公开的，且历史提交中包含密钥和口令，因此不能把「改了仓库可见性」理解为密钥泄露已经消失。正式交付前至少完成下面第 1 项；对外分发建议完成第 2 项。

### 1. 立即改为私有

在 GitHub 仓库页面进入 **Settings → General → Danger Zone → Change repository visibility → Private**。这能阻止公众继续访问仓库和构建产物，但无法撤销已经被获取的旧密钥。

### 2. 轮换签名并移入 Secrets

1. 在可信电脑上重新生成 PKCS12 密钥，保存新别名和新口令。
2. 在仓库 **Settings → Secrets and variables → Actions → New repository secret** 添加：
   - `RELEASE_STORE_BASE64`：新 keystore 的 Base64 文本
   - `RELEASE_STORE_PASSWORD`：新存储口令
   - `RELEASE_KEY_ALIAS`：新别名
   - `RELEASE_KEY_PASSWORD`：新密钥口令
3. 修改 `.github/workflows/build.yml`，在 checkout 后解码密钥到 `$RUNNER_TEMP/stockwatch.p12`，再通过 Gradle 属性把路径和三个口令传给 `assembleRelease`；不要把秘密写进日志或提交到仓库。
4. 从工作树删除 `keystore.p12` 和 `gradle.properties` 中的签名口令。旧密钥已经失效，历史提交中的旧文件不再用于新包。
5. 第一次安装新签名包前需卸载旧包，App 私有配置和提醒记录会随之清除，请先在面板记录好配置。

## 三、手机侧必须设置的项（国产 ROM）

Android 13+ 的 Doze 和各厂商 ROM 都可能限制后台，前台服务能显著提高存活率，但不能保证所有机型永久不被杀。下面几项不是可选项：

1. **通知权限**：首次启动会申请；拒绝后到「设置 → 应用 → 盯盘助手 → 通知」打开。
   同时确认「盯盘状态」「做T提醒」「乖离、交叉与代理」「当日涨跌幅」「运行异常」五个通道都没被关闭。
2. **电池优化豁免**：在 App 首页点「申请忽略电池优化」。
   小米/红米额外检查「省电与电池 → 应用智能省电」；荣耀/华为检查「启动管理 → 手动管理，三项全开」；OV 检查「电池 → 后台耗电管理」。
3. **自启动和后台联网**：在 App 首页点「打开厂商自启动设置」，允许自启动、关联启动和后台联网。
4. **重启恢复**：保持自启动开启；手机重启后 App 的开机广播会尝试恢复前台服务。不同 ROM 仍可能要求用户手动确认。

App 首页会显示前台服务、通知权限、电池优化和当前启用标的数量。通知栏里的「盯盘状态」是前台服务通知，不要把它当普通提醒划掉。

## 四、使用本地面板

1. 在 App 首页点 **打开配置面板**。
2. 面板由 App 内的 `127.0.0.1:8760` 服务提供，WebView 只加载本机页面，不连接公网面板。
3. 在「标的清单」添加代码，可带 `sh/sz/bj` 前缀；点「解析」补全名称和类型。
4. 按需填写乖离、交叉、涨跌幅、代理和做T 参数。留空的单项参数使用全局值。
5. 点 **保存配置**。保存成功后才写回 `config.json`，总览和前台服务读的都是已保存文件；页面显示「有未保存修改」时，刷新总览不会改变实际盯盘配置。
6. 点 **测试本地通知**，确认手机通知栏能收到测试消息。
7. 点 **启动盯盘**，然后退出面板。关闭面板只会关闭本机 HTTP 服务，不会停止已经启动的前台盯盘服务。

面板每次打开会生成随机访问凭证，API 请求必须携带该凭证；HTTP 服务只绑定回环，Activity 销毁后立即关闭。面板与前台服务共用同一个 `WatchEngine` 和 `AlertState`，不会出现两份去重记录互相覆盖。

总览有 20 秒缓存；点「刷新总览」会强制取新。做T 按状态翻转提醒：第一次破档提醒一次，回到区间内静默重新武装；通知发送失败不会记录为已提醒。

## 五、从 PC 配置迁移到手机

安卓版没有企业微信配置，`wecom` 段不会写入安卓配置文件。其余主要字段名与 PC 版一致：

```text
poll_seconds / sessions / kline_bars / nav_bars / min_history_bars
bias_mas / bias_enabled / bias_upper_pct / bias_lower_pct
bias_cooldown_minutes
cross / change / t_range / proxy / stocks
```

`stocks` 中继续支持 `code`、`type`、`name`、`enabled`、`proxy`、乖离单项覆盖、`cross`、`change`、`t_range`、`proxy_rules`。

当前不提供把 PC 文件直接推送到 App 私有目录的按钮。迁移时按上述字段在手机面板重新填写；PC 的 `data/state.json` 也不会自动迁移，因此同一天首次在手机上运行可能重新提醒一次。

配置文件位于 App 私有目录 `files/config.json`，保存旧版时备份为 `files/config.backup.json`；提醒状态位于 `files/data/state.json`，缓存位于 `files/data/`。卸载 App 会清除这些数据。

## 六、真机验收清单

- [ ] APK 能安装并显示版本页。
- [ ] 通知权限、五个通知通道、电池优化和自启动均已开启。
- [ ] 面板添加一只场内标的和一只场外基金，保存后关闭再打开，配置仍在。
- [ ] 「测试本地通知」能在通知栏看到消息。
- [ ] 交易时段启动服务，锁屏等待至少两个轮询周期，状态通知和行情仍在更新。
- [ ] 触发一次乖离或涨跌幅提醒，确认同一规则按冷却时间去重。
- [ ] 做T 临时把基准价设到接近现价并用较小偏离，确认出档提醒一次，回区间后可再次武装。
- [ ] 重启手机，确认前台服务按预期恢复。
- [ ] 退出面板后后台提醒继续，重新打开面板能看到同一份最近提醒记录。

模拟接口下已验证页面加载、做T 价格计算、配置保存、代理规则保留、测试通知、服务启停和凭证拒绝；Android 真机 WebView、厂商后台策略及实际通知仍需按以上清单验收。

## 七、数据源使用约束

腾讯行情和天天基金接口仅用于个人自用盯盘，不要用于高频请求或对外分发。App 默认 30 秒轮询，日线和净值有本地缓存；不要为了测试把轮询间隔长期改成 5 秒。
