# 自托管版本站点

公开地址：`https://acupofttu.top/`。网页和更新清单位于 `dist/`，APK 生成在 `dist/downloads/`；APK 不提交到 Git。

双击仓库根目录的 `scripts/publish-release.cmd` 会依次准备新版本、上传本站、提交并推送 Gitee 代码，再创建 Gitee Release。也可在 PowerShell 中运行：

```powershell
.\scripts\publish-all.ps1 -ReleaseNotes @("修复问题", "改进体验")
```

流程会提升 APP 版本号、构建并验证已签名 APK、计算 SHA-256、更新 `dist/update.json`，再通过 `meddeploy` SSH 账号上传到服务器，并检查线上清单与 APK。之后会提交应用、版本文件和发布脚本，推送到 Gitee `main`，创建 Release 并上传 APK 与更新清单。签名配置从被 Git 忽略的 `keystore.properties` 读取；SSH 私钥位于当前 Windows 用户的 `.ssh/medical_record_deploy_ed25519`。首次输入 Gitee 私人令牌并成功发布后，脚本会将其以 Windows 用户加密格式保存到被 Git 忽略的 `scripts/.env`；之后同一 Windows 用户可直接复用。也支持设置环境变量 `GITEE_TOKEN`。

如果本地已准备好版本（例如当前的 0.5.6），运行 `.\scripts\publish-release.cmd -Resume` 会复用 APK，不再增加版本号。任一步骤失败后也使用 `-Resume` 重试。仅需补传本站时可运行 `scripts/deploy-prepared-release.ps1`；仅需补传 Gitee 时可运行 `scripts/publish-gitee-release.cmd`。等待备案期间如只需 Gitee，可加 `-SkipSite`。

旧 Sites 地址保留了 0.5.2 过渡版本。0.5.1 APP 通过旧地址升级到 0.5.2 后，后续版本改由 `acupofttu.top` 提供。

## 单独补传 Gitee Release

先将当前版本代码提交并推送到 `gitee` 的 `main`，确保 `server-site/dist/update.json` 对应的已签名 APK 位于 `server-site/dist/downloads/`。然后双击 `scripts/publish-gitee-release.cmd`，或运行：

```powershell
.\scripts\publish-gitee-release.ps1
```

脚本核对版本和 APK SHA-256，创建并推送 `v版本号` 标签，再通过 Gitee API 创建 Release、上传 APK 和 `update.json` 附件。附件中的 `download_url` 自动指向同一 Release 的 APK；本地 `dist/update.json` 保留自托管站点地址。Release 标题从 UTF-8 的 `update.json` 读取。若上传中断，重新运行会复用已有标签和 Release，并补齐缺失的附件、修正不一致的标题。发布前需先在 Gitee 创建一次私人令牌。此脚本只发布已经构建的 APK，不生成新版本。准备后续版本时，可先运行 `publish-release.ps1 -PrepareOnly`，提交版本文件并推送到 Gitee，然后运行此脚本。

从下一版开始，App 通过 Gitee 的最新 Release API 查找 `update.json`，再按其中的 SHA-256 校验 APK。已经安装的 0.5.5 仍使用原域名检查更新，首次切换需要手动安装包含此改动的新版本，或等待原域名可访问后升级。重新运行 Gitee 脚本可以为 0.5.5 Release 补传 `update.json` 附件，不会改变版本号。
