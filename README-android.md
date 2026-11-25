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
- [ ] 配置界面（内嵌 `127.0.0.1` 服务 + WebView 加载 PC 版面板）

## 技术取向

- **零 AndroidX、零第三方库**：只用 framework API 和 `HttpURLConnection`，Gradle 构建只需 Android SDK，CI 首次通过率最高，也延续 PC 版「零依赖」的做法。测试期唯一例外是 `testImplementation junit`，不进包。
- **单模块 `app`**，但 `com.stockwatch.app.core` 包内不允许出现任何 `android.*` 依赖，保证规则引擎能在 JVM 上直接跑单元测试。
- minSdk 26 / targetSdk 34。

## 一、首次出包（需要你手动做一次）

本机没有 `gh` CLI，也没有 JDK/Android SDK，所以构建交给 GitHub Actions。

1. 在 https://github.com/new 建一个**私有**仓库，名字 `stock-watch-android`，不要初始化 README/.gitignore（本地已有）。
   仓库必须是私有：`keystore.p12` 和 `gradle.properties` 里的签名口令是随仓库走的（见下方「签名」）。
2. 在本目录执行（把名字邮箱换成你自己的，`-c` 只影响这一次提交）：

```bash
git init -b main
git add -A
git -c user.name="你的名字" -c user.email="你的邮箱" commit -m "Android 骨架 + Actions 出包链路"
git remote add origin https://github.com/你的账号/stock-watch-android.git
git push -u origin main
```

   第一次 push 会弹浏览器让你登录 GitHub（Git 凭据管理器）。

3. 打开仓库的 **Actions** 页，等 `build-apk` 跑完（首次约 3～6 分钟），在这次 run 的 **Artifacts** 里下载 `stock-watch-release-apk`，解压得到 `app-release.apk`。
4. 传到手机点击安装，允许「未知来源」。装好后屏幕上应显示 App 版本、Android 版本与机型 —— 看到这三行就说明流水线通了。

后续每次 push 都会自动出新的 APK，签名不变，**直接覆盖安装即可**，不用先卸载。

## 二、签名

`keystore.p12`（RSA 2048，有效期至 2054 年）用 openssl 本地生成，别名 `stockwatch`，PKCS12 存储口令与密钥口令相同。AGP 直接读它给 release 签名，好处是 Actions 每次出的包签名一致，能覆盖安装。

个人自用把密钥和口令放在仓库里（因此仓库必须私有）。要改成不进仓库的做法：把 Actions 里 `checkout` 之后加一步把 base64 还原成 keystore，并从 GitHub Secrets 读 `RELEASE_STORE_PASSWORD`，同时删掉仓库里的 `keystore.p12` 与 `gradle.properties` 中那四行。

## 三、手机侧必须做的三件事（国产 ROM）

Android 13+ 的 Doze 加上各家 ROM 的杀后台，会让「后台常驻」这件事十次有八次失败，下面三条不是可选项：

1. **通知权限**：首次启动会弹申请；拒绝后需在「设置 → 应用 → 盯盘助手 → 通知」里打开。
2. **电池优化豁免**：「设置 → 电池 → 应用省电策略 → 盯盘助手 → 无限制」。小米/红米在「省电与电池 → 应用智能省电」，荣耀/华为在「启动管理 → 手动管理，三项全开」，OV 在「电池 → 后台耗电管理」。
3. **自启动**：同上路径里的「允许自启动 / 允许关联启动」，否则重启手机后不会恢复盯盘。

App 内会做一个自检页，把这三项的当前状态显示出来，缺哪项直接跳到哪项的设置页。

## 四、与 PC 版的行为差异

- **提醒通道**：只有本地通知，没有企业微信；`config.json` 的 `wecom` 段和 60020 那类坑在安卓版不存在。
- **记录位置**：去重状态存在 App 私有目录 `filesDir/data/state.json`，卸载即丢；PC 版是 `data\state.json`。
- **面板**：安卓版仍想在手机上留一个配置界面，做法是把 PC 版 `panel.html` 打进 assets，由 App 内的 `127.0.0.1:8760` 小服务提供 `/api/*`。监听地址只绑回环，不外露。
