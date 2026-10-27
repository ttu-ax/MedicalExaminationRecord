# 发布脚本说明

本目录用于 Windows 下构建签名 APK、发布 Gitee Release，以及部署自建下载站。下面的命令均从**仓库根目录**执行；`.cmd` 适合双击运行，`.ps1` 适合在 PowerShell 中传入参数运行。

## 先选用哪个脚本

| 场景 | 推荐入口 |
| --- | --- |
| 发布新版本到 Gitee，不访问自建站 | `publish-gitee.cmd` 或 `publish-gitee.ps1` |
| 同时发布到 Gitee 和自建站 | `publish-release.cmd` 或 `publish-all.ps1` |
| 只在本地准备新版本 APK 和清单 | `publish-release.ps1 -PrepareOnly` |
| 已经构建好，只补传 Gitee Release | `publish-gitee-release.cmd` 或 `publish-gitee-release.ps1` |
| 已经构建好，只补传自建站 | `deploy-prepared-release.ps1` |
| 发布中断，继续发布同一个已准备版本 | 原来的一键发布入口加 `-Resume` |

**注意同名入口的区别：`publish-release.cmd` 调用的是 `publish-all.ps1`，会执行双渠道发布；直接运行 `publish-release.ps1` 默认只构建并部署自建站。**

## 各脚本的作用

### `publish-gitee.cmd`

Gitee 一键发布的双击入口，调用 `publish-gitee.ps1`。执行结束后显示结果并暂停窗口，参数会传给 PowerShell 脚本。

### `publish-gitee.ps1`

Gitee 专用的一键发布流程：

1. 检查签名配置和 Gitee 令牌是否已配置。
2. 如果本地缺少 `gitee` 远端，添加 `https://gitee.com/afeng66/MedicalExaminationRecord.git`；已有远端时核对地址。
3. 调用 `publish-all.ps1 -SkipSite`，完成版本准备、构建验签、提交代码、推送 Gitee、创建 Release 和上传附件。

支持版本参数以及 `-Resume`，不会访问自建站。未传 `-ReleaseNotes` 时，会提示逐条输入更新说明，输入空行结束。

### `publish-release.cmd`

双渠道一键发布的双击入口，调用 `publish-all.ps1`。执行结束后显示结果并暂停窗口，支持透传 `-Resume`、`-SkipSite` 等参数。

### `publish-all.ps1`

完整发布流程的编排脚本，要求当前分支为 `main`，且开始时没有已暂存的文件：

1. 调用 `publish-release.ps1 -PrepareOnly` 准备新版本；传入 `-Resume` 时复用已有 APK 和清单。
2. 调用 `deploy-prepared-release.ps1` 部署自建站；`-SkipSite` 可跳过此步骤。
3. 暂存指定范围内的改动，有改动时提交为 `Publish 版本号`，然后推送到 Gitee `main`。
4. 调用 `publish-gitee-release.ps1` 创建 Release 并上传附件。

自动暂存范围包括根目录 `README.md`、`app/src/main`、`app/src/test`、`app/build.gradle.kts`、`server-site/README.md`、`server-site/dist/update.json` 和整个 `scripts` 目录，因此执行前应检查这些路径中的待发布改动。

自建站部署失败时，脚本仍会继续发布 Gitee，最后报告网站失败；可以单独补传网站。**此脚本只推送 Gitee，不会自动推送 GitHub，也不会创建 GitHub Release。**

### `publish-release.ps1`

准备一个新版本，并按参数决定是否部署自建站：

- 默认将 `versionCode` 加 1、将三段式 `versionName` 的最后一段加 1，也可以显式指定版本。
- 检查签名配置及必要源码功能，构建 release APK，并使用 Android SDK 中的 `apksigner` 验签。
- 复制 APK 到 `server-site/dist/downloads/medical-record-v版本号.apk`。
- 计算 SHA-256，更新 `server-site/dist/update.json` 中的版本、更新说明、下载地址等字段。
- 加 `-PrepareOnly` 时到此结束；不加时继续调用 `deploy-prepared-release.ps1`。

此脚本不会提交代码、推送代码或创建 Gitee Release。

### `publish-gitee-release.cmd`

单独补传 Gitee 的双击入口，调用 `publish-gitee-release.ps1`。只处理已有版本，不会构建 APK 或提升版本号。执行结束后暂停窗口，便于查看结果。

### `publish-gitee-release.ps1`

发布已准备好的 APK 和清单到 Gitee：

- 校验 Android 版本、清单版本和 APK SHA-256 是否一致，并要求版本文件已提交、Gitee `main` 与本地 `HEAD` 一致。
- 核对本地与远端版本标签，必要时创建并推送 `v版本号` 标签。
- 创建或复用对应 Release，补齐 APK 和 `update.json` 附件，必要时修正 Release 标题。
- 上传的 `update.json` 使用 Gitee APK 下载地址，本地清单仍保留自建站地址。

重新运行会跳过已有同名附件，适合中断后补传缺失附件；它不会替换已有附件，也不会重新构建 APK。

### `deploy-prepared-release.ps1`

将已有 APK 和清单部署到自建下载站，不提升版本号：

1. 校验清单、预期下载地址和 APK SHA-256。
2. 将 APK 和清单同步到独立的多应用网站项目，更新 `apps.json` 中的体检记录条目，保留首页和其他应用。
3. 调用网站项目的 `scripts/deploy-site.ps1` 上传部署。

默认网站项目位于本仓库相邻的 `../app-release-site`，可通过 `-ReleaseSiteDirectory` 指定位置。网站的 SSH、服务器连接和上传行为由该项目的部署脚本负责。

## 首次使用前的配置

- **构建环境**：JDK 17+、Android SDK，以及 `ANDROID_SDK_ROOT`、`ANDROID_HOME` 或根目录 `local.properties` 中的 SDK 路径。
- **签名**：根目录 `keystore.properties`，格式参考 `../keystore.properties.example`。`storeFile` 相对仓库根目录解析，例如 `android-keystore/net.lvtao.tool.keystore`；应使用之前发布版本的同一份密钥。
- **Gitee 令牌**：在根目录 `.env` 中添加 `GITEE_TOKEN=你的令牌`，已有配置只追加，不覆盖。附件发布脚本读取优先级为环境变量 `GITEE_TOKEN`、根目录 `.env`、旧版 `scripts/.env`；旧版文件也支持 Windows 用户加密的 `GITEE_TOKEN_DPAPI`。单独的附件发布流程在未找到令牌时会提示输入，并保存加密令牌；一键 Gitee 入口要求提前配置。
- **Git 推送凭据**：Gitee API 令牌与 Git 推送认证分别使用各自配置；本机需能正常向 `gitee` 远端推送。除 `publish-gitee.ps1` 会自动添加远端外，其他发布入口要求事先配置好 `gitee`。
- **网站项目**：双渠道发布和单独网站部署需要准备 `../app-release-site` 及其部署配置。

`.env`、`keystore.properties` 和 `android-keystore/` 已被 Git 忽略，请勿将令牌、签名私钥或密码文件加入版本控制。

## 常用命令

只发布 Gitee，自动递增版本号：

```powershell
.\scripts\publish-gitee.ps1 -ReleaseNotes @("报告分类按数量排序", "支持指标置顶")
```

同时发布 Gitee 和自建站：

```powershell
.\scripts\publish-all.ps1 -ReleaseNotes @("修复问题", "改进体验")
```

继续发布已经准备好的同一个版本，不重新构建：

```powershell
.\scripts\publish-gitee.ps1 -Resume
# 双渠道流程则使用：
.\scripts\publish-all.ps1 -Resume
```

只生成本地发布文件：

```powershell
.\scripts\publish-release.ps1 -PrepareOnly -ReleaseNotes @("修复问题")
```

只补传某个渠道：

```powershell
# Gitee：要求代码和版本文件已提交并推送到 Gitee main
.\scripts\publish-gitee-release.ps1
# 自建站：要求本地已有对应 APK 和清单
.\scripts\deploy-prepared-release.ps1
```

本机代理导致 Gitee API 连接异常时，可让 Gitee API 直连：

```powershell
# Gitee 专用入口没有直连参数，此时使用编排脚本
.\scripts\publish-all.ps1 -SkipSite -GiteeDirectConnection -ReleaseNotes @("修复问题")
# 已准备好版本时补传附件
.\scripts\publish-gitee-release.ps1 -DirectConnection
```

上述直连参数仅影响 Gitee API 请求，不改变 Git 推送或网站部署的连接方式。

## 参数速查

| 参数 | 适用脚本 | 作用 |
| --- | --- | --- |
| `-VersionName` | `publish-gitee.ps1`、`publish-all.ps1`、`publish-release.ps1` | 指定新版本名；Gitee Release 要求三段式数字版本 |
| `-VersionCode` | 同上 | 指定新的版本号，必须高于当前应用和清单中的版本号 |
| `-ReleaseNotes` | 同上 | 更新说明字符串数组；未提供时交互输入 |
| `-MinimumSupportedVersionCode` | 同上 | 设置最低支持版本号，默认沿用当前清单 |
| `-ForceUpdate` | 同上 | 在更新清单中标记强制更新 |
| `-Resume` | `publish-gitee.ps1`、`publish-all.ps1` | 复用已有发布文件，不能同时传入上面的版本与更新说明参数 |
| `-SkipSite` | `publish-all.ps1` | 跳过自建站部署 |
| `-GiteeDirectConnection` | `publish-all.ps1` | Gitee API 不使用代理 |
| `-PrepareOnly` | `publish-release.ps1` | 仅构建及生成清单，不部署网站 |
| `-SiteDirectory` | `publish-release.ps1`、`deploy-prepared-release.ps1` | 本仓库内的发布文件目录，默认 `server-site` |
| `-PublishOrigin` | `publish-release.ps1` | 写入清单的下载站根地址，默认 `https://acupofttu.top`；网站部署脚本当前会严格核对该默认地址，不能仅改此参数就切换网站 |
| `-ReleaseSiteDirectory` | `deploy-prepared-release.ps1` | 独立网站项目目录，默认 `../app-release-site` |
| `-Owner`、`-Repository`、`-Remote` | `publish-gitee-release.ps1` | 指定 Gitee 仓库拥有者、仓库名和本地远端名；默认分别为 `afeng66`、`MedicalExaminationRecord`、`gitee` |
| `-DirectConnection` | `publish-gitee-release.ps1` | Gitee API 不使用代理 |

## 发布失败后怎么处理

- **签名路径或配置错误，尚未生成新版本**：修正配置后正常重跑，无需 `-Resume`。
- **已经生成版本 APK 和清单**：使用 `-Resume` 重试，避免误生成下一版本。恢复流程会校验版本、哈希和源码修改时间；准备后又修改了源码时，不能继续发布旧 APK。
- **只有网站失败、Gitee 已成功**：运行 `deploy-prepared-release.ps1`。
- **只有 Release 附件未传完**：运行 `publish-gitee-release.ps1`，补齐缺失附件。
- **需要将发布提交同步到 GitHub**：发布完成后执行 `git push origin main`；先确认本地 `origin` 指向预期 GitHub 仓库。

进一步的网站与更新机制说明见 [server-site/README.md](../server-site/README.md)。
