# SoIM 正式签名与迁移

## 签名所有权

长期 Release Key 必须由项目所有者创建和保管，Codex、CI 日志与仓库都不应持有私钥或密码。建议至少保存两份离线加密备份，并单独保存 alias、证书 SHA-256 和恢复说明。`keystore.properties`、JKS/keystore 文件以及密码不得提交 Git。

项目所有者可在受信任的离线位置创建密钥（密码应由所有者交互输入）：

```powershell
D:\soft\ide\jdk\azul-17.0.12\bin\keytool.exe -genkeypair -v -keystore soim-release.jks -alias soim -keyalg RSA -keysize 4096 -validity 10000
D:\soft\ide\jdk\azul-17.0.12\bin\keytool.exe -list -v -keystore soim-release.jks -alias soim
```

创建后必须完成两份离线加密备份，并把 `SHA256:` 后的 64 位公开证书指纹写入根目录 `release-identity.properties`。该文件是 Android 迁移校验与发布脚本共用的公开身份源；空值表示迁移严格禁用。

本地构建可复制 `keystore.properties.example` 为 `keystore.properties`，或设置以下环境变量：

- `SOIM_SIGNING_STORE_FILE`
- `SOIM_SIGNING_STORE_PASSWORD`
- `SOIM_SIGNING_KEY_ALIAS`
- `SOIM_SIGNING_KEY_PASSWORD`
- `SOIM_SIGNING_CERT_SHA256`（正式发布脚本要求）

只有前四项完整时 Gradle 才启用 Release 签名。正式发布脚本会把实际证书指纹与 `release-identity.properties` 比较，拒绝 Debug 证书或错误证书。`SOIM_SIGNING_CERT_SHA256` 可作为本地二次断言，但不能覆盖仓库固定身份。

## Debug 到 Release 的迁移

Android 不允许使用新的、无 lineage 关系的 Release 证书覆盖当前 Debug 签名安装。当前测试用户必须选择下列迁移方式之一：

1. 继续使用 Debug 签名测试渠道，正式版作为一次明确的卸载重装迁移；卸载前先使用 SoIM 完整备份导出数据，重装后恢复并重新关联 MediaStore 图片。
2. 若正式发行平台支持且能够证明旧签名所有权，评估平台的密钥升级/签名 lineage 能力；Android Debug Key 通常不适合作为长期 lineage 起点。

在真实迁移演练完成前，不得用 Release APK 替换现有 Debug Release 资产，也不得删除 Debug 分发兜底。

## 正式发布

先把版本、版本记录和用户可见更新说明提交，再运行：

```powershell
.\scripts\publish-github-release.ps1 -Notes docs\releases\v0.x.y.md
```

该命令只构建和校验，不上传。确认版本、SHA-256 和 signer SHA-256 后，显式发布：

```powershell
.\scripts\publish-github-release.ps1 -Notes docs\releases\v0.x.y.md -Publish
```

首个正式版本必须先以 prerelease 发布：

```powershell
.\scripts\publish-github-release.ps1 -Notes docs\releases\v0.19.0.md -Prerelease -Publish
```

脚本执行 Debug/Release 单元测试、Lint、AndroidTest 源码编译、Release 构建、APK 元数据、签名和证书指纹检查，然后直接把 `app-release.apk` 上传至 GitHub Release。更新说明只写用户可见变化，构建和验证结果留在发布日志中。

从 `v0.19.0` 起，Gradle 本身也要求长期 keystore 和 64 位固定证书指纹；缺少任一项时构建直接失败。`v0.18.2` 仍允许生成 Debug 迁移准备包。
