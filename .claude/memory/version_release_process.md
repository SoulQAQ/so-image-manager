---
name: version-release-process
description: APK版本发布流程和版本号管理规范
metadata: 
  node_type: memory
  type: project
  originSessionId: 6edfbae0-1454-443f-b340-5c54524c783c
---

# APK版本发布流程

## 版本号管理

- 当前版本存储在 `apk/current_version_is_x.x.x` 文件
- 版本变更日志在 `apk/ver_change_log.md`
- 版本号格式：`major.minor.patch`（如 0.2.0）

## 版本号修改位置

打包前必须修改以下位置：

1. `app/build.gradle.kts`:
   ```kotlin
   versionCode = 2  // 递增
   versionName = "0.2.0"
   ```

2. `apk/current_version_is_0.2.0` 文件名（删除旧文件，创建新文件）

3. `apk/ver_change_log.md` 添加变更记录

## 发布流程

1. 修改 `app/build.gradle.kts` 的 versionCode 和 versionName
2. 更新 `apk/current_version_is_x.x.x` 文件
3. 更新 `apk/ver_change_log.md` 变更日志
4. 构建 APK: `./gradlew assembleRelease`
5. 复制并重命名 APK 到 `apk/` 目录

## Why

用户要求每次发布新版本时自动管理版本号，确保在安卓系统应用详情中能看到正确的版本信息。

## How to apply

每次打包发布时，按上述流程操作。版本号升级规则：
- major: 重大架构变更或重构
- minor: 新功能模块完成
- patch: bug修复或小改进
