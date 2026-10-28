# 晴川 · Bright SDK Android 本地构建工程

应用显示名称：晴川  
Application ID / namespace：`com.qingchuan.connect`  
宿主版本：`0.1.0`（Bright 集成独立版本线，不是旧 PROXIES.SX 应用升级包）

## 隐私政策与常见问题

本应用仅通过 GitHub 以 APK 形式发布，未上架 Google Play 或其他应用商店。

- 隐私政策：https://tlici.github.io/qingchuan/privacy.html
- 常见问题（Bright Data / Web Indexing）：https://tlici.github.io/qingchuan/faq.html
- Bright SDK 官方终端用户说明：https://brightdata.com/sdk/information

应用内同样可达：设置 →「隐私与数据」→「查看完整隐私政策」，设置 →「关于」→「常见问题」。

## 当前交付状态（2026-10-23）

本包是源码和本地构建工具，不是已构建 APK。
当前制作环境没有 Android SDK/Gradle，外网 DNS 解析失败，未下载 Bright 官方 AAR，未编译 Android APK，也未运行真机测试。没有使用对话中提供的 API key，没有验证该 key 是否有效。

已经完成：宿主界面和授权控制、下载及构建脚本、版本和哈希记录机制、SDK API 字节码检查、离线工具测试。工具测试使用临时接口夹具，只证明工具逻辑和接口生成逻辑；它们不能证明真实 SDK 的编译和运行兼容性。临时夹具不进入 app/libs，不会生成或替代正式 SDK。

## 1. 本机准备

安装 Python 3.10 或更新版本、JDK 17 或 21、Android SDK Platform 35 和 Android SDK Build-Tools 35.0.0。推荐通过 Android Studio 的 SDK Manager 安装 Android SDK 并自行阅读、接受相关许可。无需安装 Rust，普通构建使用官方已经发布的下载器。

脚本会检查 JAVA_HOME、ANDROID_HOME、ANDROID_SDK_ROOT 和常用 Android Studio 安装位置。缺少时会明确停止；不自动接受许可。

Windows 常用 Android SDK 目录：`%LOCALAPPDATA%\Android\Sdk`。

在本工程根目录打开终端，先检查：

```powershell
py -3 tools\build.py doctor
```

## 2. Windows 构建

```powershell
.\build.cmd
```

当终端提示 `Bright SDK API key (hidden; not saved):` 时，粘贴你自己的 key 并按回车，输入不会回显。不要把 key 写入命令行参数、源码、Gradle 配置或 Android UI。交互输入方式避免把 key 字面值写入 PowerShell 命令历史。

发布签名构建：

```powershell
.\build.cmd --release
```

release 构建需要工程根目录下的 `keystore.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`）与对应的 keystore 文件。两者都已在 `.gitignore` 中，不会进入仓库。缺少 `keystore.properties` 时脚本会直接停止，不会产出未签名的 APK。

如果 JAVA_HOME 没有配置，可指向 Android Studio 的 JDK 17/21 目录。脚本也会尝试自动找到它。

## 3. Linux/macOS 构建

```bash
chmod +x build.sh
./build.sh
```

同样在隐藏输入提示处输入 key。非交互式 CI 可以使用名为 `SDK_API_KEY` 的 secret；不要在公开工作流或日志中填写字面值。

## 4. 下载与构建实际做什么

1. 从 BrightSDK 官方 GitHub Releases 解析当前正式下载器，记录发行 tag 和 SHA-256，并在本地固定该二进制。
2. 仅在官方 SDK 下载子进程中传递 `SDK_API_KEY`；Gradle、Java 和其他构建子进程不继承该变量。
3. 解析 Android SDK 的实际版本，下载 SDK，定位真实 `bright_sdk.aar`。
4. 检查真实 AAR 的 `BrightApi`、`Settings`、授权回调和英文同意界面 API。发现与指南不同的 API 时停止，不猜测包名，也不自动退回假 SDK。
5. 自动生成静态类型绑定，并保存 `sdk-lock.json`。
6. 获取校验过的 Gradle 8.11.1，使用 Android Gradle Plugin 8.9.2 构建 debug APK，并运行 Android Lint。
7. 仅在包名、应用名称、签名、真实 BrightApi DEX 存在性与 AAR 哈希检查通过后，把 APK 复制到 `output/`。若该次提供了 key，还检查 APK 是否包含其明文字面值。
8. 后续构建默认复用锁定的 AAR。显式更新才使用 `--refresh-sdk`，避免无意改变实验版本。

`SDK_API_KEY` 是 SDK 下载凭据，不是本项目提供的手机端输入项。运行时 App ID 由 `com.qingchuan.connect` 决定，必须与 Bright 控制台注册记录一致。

## 5. 预期输出（构建成功后才存在）

```text
output/
  Qingchuan-Bright-0.2.0-sdk-实际版本-release.apk   （或 -debug.apk）
  build-manifest.json
  SHA256SUMS.txt
sdk-lock.json
app/libs/bright_sdk.aar
app/src/generated/java/com/qingchuan/connect/SdkBinding.java
```

文件名中的版本号取自 `app/build.gradle` 的 `versionName`，不再由脚本另行写死。

本交付包没有这些真实构建产物。不要把源码压缩包改扩展名为 APK。

## 6. 已经下载 AAR 的情况

可以在本机离线导入已经正规获取的 AAR：

```powershell
py -3 tools\build.py import-sdk --aar "C:\your-folder\bright_sdk.aar" --sdk-version "实际版本"
.\build.cmd
```

这里只是无需再次下载 SDK；初次获取 Android 依赖和 Gradle 仍需要网络。已缓存完整依赖后才具备离线构建条件。

只下载 SDK、不要求本机已有 Android SDK：

```powershell
py -3 tools\build.py fetch
```

自行提供官方 CLI 二进制时：

```powershell
py -3 tools\build.py fetch --downloader "C:\your-folder\bright-sdk-downloader-win-x64.exe"
```

可选：有 Rust 与 Git 的用户，可从官方正式发行 tag 构建下载器；此步骤不是 Android APK 构建：

```powershell
py -3 tools\build.py downloader --from-source
```

## 7. 界面与授权

应用名称及包名不包含“研究”“代理”。但应用明确说明 Bright Data 将使用空闲网络资源与 IP 地址访问公共网页。保留官方同意界面、Web Indexing 开关、即时退出按钮、隐私政策和许可链接。初次打开只初始化；开启必须由用户在官方界面确认，程序不调用自动同意接口。

界面显示的是授权状态和本应用 UID 收发字节，不声称它代表已收到客户任务、收益或实际业务转发。代码不强制常驻、不绕过后台限制、不添加 PROXIES.SX SDK。

默认英文官方授权界面；中文仅用于宿主说明。不要擅自翻译或替换厂商法律同意界面。

## 8. 实际运行与厂商审核

在 Bright 后台登记 `com.qingchuan.connect`，自行确认用户规模、用途和条款。完成构建后，在你拥有或获授权的真机安装 APK，打开 Web Indexing，并按官方弹窗操作。退出使用 `BrightApi.optOut()`。

本工程没有通过 Bright 的账户审核或集成审核，也不保证接入后有任务。应按官方指南在真机执行 Implementation self-check。官方指南提到 `cskd/cid` 日志，但看到日志或授权状态不等于自然客户转发已经发生。

需要更新本应用时，保管本机 Android debug 签名文件（默认在用户的 .android 目录）；换机器或丢失签名可能无法覆盖安装。

## 9. 凭据处理

本包不含任何用户 API key，不写运行时账户密钥。官方下载器通过 `SDK_API_KEY` 获取凭据。脚本不会记录该值，并对可见错误做字面值脱敏；这不等于对操作系统、终端、CI 或聊天平台存储作保密保证。不要公开系统转储或完整环境变量。

如果 key 曾在聊天、工单或公开终端中发送，建议在 Bright 后台撤销并新建短期 key，之后仅在本地隐藏输入。不要把替换 key 再贴到聊天。

## 10. 测试与来源

```powershell
py -3 -m unittest discover -s tests -v
```

20 项离线测试覆盖 XML、资源引用、Application ID、凭据隔离、ZIP 路径检查、元数据解析和 Java 类型绑定生成。SDK 夹具不包含代理代码，不是可运行 Bright SDK。

官方参考：
- https://github.com/BrightSDK/bright-sdk-downloader-rs
- https://brightsdk.github.io/bright-sdk-downloader-rs/
- https://docs.bright-sdk.com/integration-guides/android-sdk
- 你上传的《Bright SDK for Android - Integration Guide》：SDK 下载与依赖、BrightApi 初始化、用户同意及退出、App ID 规则。
