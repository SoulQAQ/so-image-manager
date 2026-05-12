## 强制版本发布规则（用户新增）
- 每次打包必须同时更新这两个文件：
  - apk/current_version_is_xxx（只保留当前版本文件）
  - apk/ver_change_log.md（记录该版本改动）
- 版本号按语义化版本（SemVer）推进：
  - 修复/小调整 -> PATCH（x.y.Z）
  - 新功能且兼容 -> MINOR（x.Y.z）
  - 破坏性改动 -> MAJOR（X.y.z）
- 每次发布后给出产物路径、版本号、关键改动、风险点。
