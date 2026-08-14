# SoIM

SoIM 是一个本地优先的 Android AI 图片管理应用。它索引设备图库，不复制原图；AI 描述、标签、分类、搜索词和用户修正保存在本机，并通过结构化、全文、子串、拼音和有限拼写容错搜索。

当前稳定版本是 `v0.18.2`；由于该包生成时正式证书尚未创建，将由 `v0.18.3` 携带固定证书身份并作为最终 Debug 迁移版本。发布状态以 GitHub Release 与版本记录为准。

## 产品能力

- Android 10（API 29）及以上的 MediaStore 全图库索引、增量同步、Paging 3 分页和任务恢复。
- 主分区、未处理分区、隐私分区和隐私无法分析子类。AI 分析成功后进入主分区，明确拒绝进入隐私分区。
- 主分区和隐私分区分别配置供应方顺序；供应方绑定一个 Base URL 和一个具体模型，支持启用、禁用、排序和自动顺延。
- OpenAI Responses、OpenAI Chat Completions、Anthropic Messages、Gemini `generateContent` 和声明式自定义 JSON 协议。
- 协议调试器可选择测试图片，展示脱敏请求、原始响应预览、映射结果和错误；不会持久化分析，也不会显示 API Key 或图片 Base64。
- 全局、供应方和模型三级并发/RPM/每日请求限制；每日图片和 Token 上限、Wi-Fi、充电、电量、执行时段、指数退避、`Retry-After`、熔断和冷却。
- 本地搜索支持文件名、相册、描述、标签、分类和搜索词，以及中文拼音、子串和有限错拼；支持 `标签:`、`分类:`、`相册:` 与明确中文自然语言筛选。
- 保存搜索、用户主题、基于现有 AI 标签/分类的推荐主题和模块化首页。单模块为连续瀑布流，多模块支持横向/网格预览、排序和数量配置。
- 完整 JSON 备份与恢复：导出图片引用、AI 历史、用户修正、供应方/模型/协议/路由和界面配置，不导出 API Key、原图、临时队列或额度账本。
- 设置页显示数据库、缓存和临时文件占用，可安全清理可重建文件。
- 图片详情保持沉浸式，文件名、时间、尺寸、格式、大小和相册信息按需展开。
- App 从 GitHub Release 检查更新，支持下载进度/速度、SHA-256 与签名兼容校验、Ready 恢复、跳过、失败重试和安装错误反馈。
- 设置页提供“迁移到正式版”向导：备份写入后重新预检，精确获取首个正式版本，校验固定正式证书，并将 APK 保存到卸载后仍保留的用户目录。

## 备份语义

恢复会先完整解析并验证备份，再与当前设备数据合并，不会先清空本机数据库。图片优先按 MediaStore volume + ID 关联，再使用唯一快速指纹；无法唯一关联的图片会报告为未匹配。现有本机凭据保留，新导入且本机不存在的供应方默认禁用且不带凭据。

备份不是原图备份，也不是加密的密钥容器。跨设备或卸载前仍需自行保护原始图片，并妥善保管自己的 API Key。

## 架构

- UI：Kotlin、Jetpack Compose、Material 3、Navigation Compose。
- 数据：Room v10、SQLite FTS4、ngram/pinyin 索引和 Paging 3。
- 图库：MediaStore、ContentObserver、WorkManager 分片同步。
- AI：OkHttp 安全传输、Android Keystore 凭据、协议适配器、canonical 结果投影。
- 更新：GitHub Release API、DownloadManager、PackageInstaller 系统流程。

Android 产品运行链路是纯 Compose。仓库根部 `h5/` 只保留为历史工程，不参与 APK 构建。

## 构建与测试

需要 JDK 17、Android SDK Platform 36、Build Tools 36.0.0、Gradle 8.9。`local.properties` 至少包含：

```properties
sdk.dir=C:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
```

常用门禁：

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat compileDebugAndroidTestKotlin
.\gradlew.bat assembleDebug assembleRelease
```

显式运行 10k/50k/100k Room 规模基准：

```powershell
.\gradlew.bat testDebugUnitTest -PsoimScaleBenchmark=true --tests cn.soul2.imageai.performance.GalleryRoomScaleBenchmarkTest --info
```

Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。本地测试版本仍通过：

```powershell
.\scripts\publish-test-apk.ps1 -Bump minor -Notes docs\releases\v0.x.y.md
```

正式签名与 GitHub Release 流程见 [release-signing.md](docs/security/release-signing.md)。

## 发布与迁移

历史安装包使用 Android Debug 证书。新的长期 Release Key 不能直接覆盖这些安装；正式切换前必须通过“导出备份 → 卸载 Debug 版 → 安装正式版 → 恢复备份”完成真机迁移演练。长期私钥必须由项目所有者创建、离线备份和保管。

`v0.18.2` 仍使用 Debug 证书，作为旧测试安装的最后迁移准备版本。`v0.19.0` 必须使用仓库固定的长期证书，先以 prerelease 提供给迁移向导；`v0.19.1` 验证同签名覆盖更新后，才停止提交 APK 并清理历史包。

长期范围基线见 [重建设计](docs/superpowers/specs/2026-07-10-image-search-rebuild-design.md)，实际完成度见 [roadmap-status.md](docs/roadmap-status.md)。

## License

MIT
