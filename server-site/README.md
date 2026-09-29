# 自托管版本站点

公开地址：`https://acupofttu.top/`。网页和更新清单位于 `dist/`，APK 生成在 `dist/downloads/`；APK 不提交到 Git。

双击仓库根目录的 `scripts/publish-release.cmd`，或在 PowerShell 中运行：

```powershell
.\scripts\publish-release.ps1 -ReleaseNotes @("修复问题", "改进体验")
```

脚本会提升 APP 版本号、构建并验证已签名 APK、计算 SHA-256、更新 `dist/update.json`，再通过 `meddeploy` SSH 账号上传到服务器，并检查线上清单与 APK。签名配置从被 Git 忽略的 `keystore.properties` 读取；SSH 私钥位于当前 Windows 用户的 `.ssh/medical_record_deploy_ed25519`。

如果本地构建成功但网络上传失败，运行 `scripts/deploy-prepared-release.ps1` 重传已准备好的版本。不要再次运行构建脚本来重试同一版本，因为它会继续增加版本号。

旧 Sites 地址保留了 0.5.2 过渡版本。0.5.1 APP 通过旧地址升级到 0.5.2 后，后续版本改由 `acupofttu.top` 提供。
