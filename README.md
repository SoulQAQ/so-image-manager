# SoIM

SoIM 是一个本地优先的 Android 图片管理应用。它扫描设备图库并在本机保存索引；用户可以按时间浏览和搜索图片，也可以为单张图片调用自定义 AI 模型生成结构化描述、标签、分类和搜索关键词。

当前已发布版本：`v0.7.0`。

## 当前能力

- Android 10（API 29）及以上的 MediaStore 图片索引、增量同步与周期校准。
- 首页瀑布流、图库时间网格、任务状态和权限管理。
- 图片详情支持左右切图、双指缩放、双击缩放以及单击显示或隐藏信息与操作层。
- 可从系统文件管理器多选图片并保留读取授权，作为 ColorOS 已选照片权限页的替代入口。
- 本地搜索覆盖文件名、图集、AI 描述、标签、分类和搜索关键词，支持全文、包含、拼音和有限近似匹配。
- 单图 AI 分析：结果以 canonical 数据保存，写入后立即可被搜索。
- 完整 AI 结果支持查看全部文本、编辑描述、增删标签/分类和恢复 AI 原值；修正会立即更新搜索。
- `openai-responses` 预设协议，以及受限、无脚本的声明式自定义 JSON 协议。
- 全局、供应方、模型三级并发、每分钟请求数和每日请求数配置。
- API Key 使用 Android Keystore 加密保存；不会写入 Room 数据库、日志或 APK。

## 使用 AI 分析

1. 首次启动时授权照片访问，等待图库建立索引。
2. 打开“设置”中的“模型、协议与并发”。
3. 配置供应方名称、Base URL、认证方式、API Key 和模型 ID；OpenAI 使用默认的 `https://api.openai.com/v1` 与 `OpenAI Responses` 协议。
4. 根据账号额度设置全局、供应方和模型的并发、RPM 与每日上限。
5. 打开图片，点击一次屏幕显示操作层，再点击“AI 分析”。分析完成后，描述和标签会显示在操作层并加入搜索。
6. 点击“查看完整结果”可阅读完整描述；在该页面保存描述或增删标签/分类后，搜索索引会立即更新。

本项目不在 APK 中提供 API Key。请使用自己拥有且允许调用的服务商凭据，并先以少量图片验证模型、账单和额度设置。

## 照片权限

- API 29–32：请求 `READ_EXTERNAL_STORAGE`。
- API 33：请求 `READ_MEDIA_IMAGES`。
- API 34+：支持完整访问或系统提供的已选照片访问。
- SoIM 不申请写入权限，不复制、移动、重命名或删除原始图片。

部分照片的选择界面由 Android 系统或 OEM 提供。ColorOS 等系统上的选择体验不能由应用重绘；可使用“设置 → 从文件管理器选择图片”作为替代入口。该入口通过 `ACTION_OPEN_DOCUMENT` 获取每张图片的持久读取授权，不依赖图库权限选择页。

## 架构

- UI：Kotlin、Jetpack Compose、Material 3、Navigation Compose。
- 本地数据：Room、SQLite FTS4、ngram/pinyin 索引和 Paging。
- 图库：MediaStore、ContentObserver、WorkManager 分片同步。
- 网络：OkHttp，显式限制重定向、请求/响应大小与请求额度。
- 图片：Coil 显示；AI 请求前使用本地预处理限制尺寸和字节数。

H5 目录仍保留历史管理界面，但当前主流程使用原生 Compose。

## 开发环境

- JDK 17。
- Android SDK Platform 36、Build Tools 36.0.0。
- Android Gradle Plugin 8.7.3、Gradle 8.9。
- 最低运行版本：Android 10（API 29）。

在项目根目录创建 `local.properties`，至少包含 Android SDK 路径：

```properties
sdk.dir=C:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
```

## 构建与测试

Windows PowerShell：

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat compileDebugAndroidTestKotlin
.\gradlew.bat assembleDebug
```

Debug APK 输出到：`app/build/outputs/apk/debug/app-debug.apk`。

发布 Debug 测试包使用：

```powershell
.\scripts\publish-test-apk.ps1 -Bump patch -Notes path\to\release-notes.md
```

该脚本会先执行 clean、Debug/Release 单元测试、Lint、AndroidTest 编译、Debug/Release 构建与 APK 元数据、签名和敏感内容检查；全部通过后才会推进 `version.properties`、`apk/current_version_is_*`、`apk/ver_change_log.md` 和版本化 APK。

## 版本规则

SoIM 使用 `主版本号.次版本号.修订号`：

- 主版本号：不兼容的 API 或数据契约修改。
- 次版本号：向下兼容的功能新增。
- 修订号：向下兼容的问题修复。

发布前必须根据实际变更选择 `publish-test-apk.ps1` 的 `major`、`minor` 或 `patch` 参数，不能把功能新增作为修订号发布。

## 目录

```text
app/                         Android 应用
  src/main/java/cn/soul2/imageai/
    ai/                      模型协议、额度、凭据、图片预处理
    analysis/                canonical AI 结果与用户修正投影
    gallery/                 图库读取模型
    media/                   MediaStore 权限与同步
    search/                  本地搜索索引与查询
    ui/                      Compose 页面与主题
apk/                         版本化测试 APK 与变更日志
docs/                        设计、计划和安全说明
scripts/publish-test-apk.ps1 测试包发布门禁
```

## 发布说明

`apk/` 下的 SoIM 安装包均为 Debug 测试包，使用 Android Debug 签名。正式生产发布必须使用独立发布证书重新签名。

## License

MIT
