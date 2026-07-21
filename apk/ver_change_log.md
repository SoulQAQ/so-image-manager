## v0.9.0 (2026-07-21)

### 功能
- 首页瀑布流支持长按多选，与图库网格操作一致。
- 选中勾选改为高对比圆形标识；操作改为底部图标加文字栏，明确区分分析、分享、从 SoIM 移除和删除原图。
- 部分照片访问提示不再全局显示，权限管理保留在设置页。
- 修复详情页连续打开不同图片时短暂显示上一张的闪屏，并按可见视口尺寸解码详情图，改善大图打开和切图流畅度。

本包用于真机测试，不创建 GitHub Release，测试确认后再公开发布。

### 修复
- 新增原子 Debug 测试 APK 发布流程，失败时回滚版本元数据与制品。

### 测试
- clean、全量单元测试、lintDebug、AndroidTest 编译、Debug/Release 构建均通过。
- 设备测试：无可用设备或 AVD，已跳过（SKIPPED_NO_DEVICE）

### 签名与校验
- Debug 签名：CN=Android Debug
- SHA-256：1A9A9E0F0D46C4DBBD1C274F341A407349D4BAC74F4041ADB51E376D1CC5DCE8

---
## v0.8.1 (2026-07-21)

### 功能
# SoIM v0.8.1 测试说明

- 修复 Android 10 删除 MediaStore 图片时的系统确认流程：确认后会实际重试删除原图，再从 SoIM 移除索引。
- `v0.8.0` 的全图库批量 AI 分析与图库长按多选功能保持不变。

本包用于真机测试，不创建 GitHub Release，测试确认后再公开发布。

### 修复
- 新增原子 Debug 测试 APK 发布流程，失败时回滚版本元数据与制品。

### 测试
- clean、全量单元测试、lintDebug、AndroidTest 编译、Debug/Release 构建均通过。
- 设备测试：无可用设备或 AVD，已跳过（SKIPPED_NO_DEVICE）

### 签名与校验
- Debug 签名：CN=Android Debug
- SHA-256：B7427E6F2925816B16D42CCAED7BAA4079391DCC79330C25C028A7536F1023BC

---
## v0.8.0 (2026-07-21)

### 功能
# SoIM v0.8.0 测试说明

- 新增全图库批量 AI 分析：任务创建后逐张处理，进度持久保存；中断后可恢复，配置、凭据、协议或请求额度问题会暂停任务。
- 新增图库长按多选：支持分析/重新分析、调用系统分享、从 SoIM 本地移除，以及经系统确认后删除 MediaStore 原图。
- 从 SoIM 移除不会删除原文件，并同步移除搜索投影；文件管理器导入的持久 URI 不参与原图删除。
- 数据库升级为 Room schema v7。

本包用于真机测试，不创建 GitHub Release，测试确认后再公开发布。

### 修复
- 新增原子 Debug 测试 APK 发布流程，失败时回滚版本元数据与制品。

### 测试
- clean、全量单元测试、lintDebug、AndroidTest 编译、Debug/Release 构建均通过。
- 设备测试：无可用设备或 AVD，已跳过（SKIPPED_NO_DEVICE）

### 签名与校验
- Debug 签名：CN=Android Debug
- SHA-256：BE5B624C42D8CF310C0077819EC18F67E133BEB309E5046D3BE3CA2381E5A620

---
## v0.7.0 (2026-07-20)

### 功能
- 修复图片详情操作层：单击图片区域才切换显示或隐藏，不再自动收起；点击顶部、底部菜单和分析按钮不会隐藏操作层。
- 完整 AI 结果可滚动查看全部描述、标签和分类，不再受底部摘要截断限制。
- 新增 AI 用户修正：可保存或清空描述、恢复 AI 描述、增删标签/分类，并恢复已删除的 AI 项；修改后搜索立即更新。
- 新增“从文件管理器选择图片”：通过 ACTION_OPEN_DOCUMENT 多选并持久保存 URI 读取授权，作为 ColorOS 已选照片权限页的替代入口。
- 文档来源图片与 MediaStore 同步隔离，校准、权限撤销和卷卸载不会将已导入文档图片误标为丢失。
- 数据库升级至 v6，保留旧图片、AI 配置和搜索数据。
- README 增加版本递增规则：不兼容修改推进主版本，功能新增推进次版本，问题修复推进修订号。

### 修复
- 新增原子 Debug 测试 APK 发布流程，失败时回滚版本元数据与制品。

### 测试
- clean、全量单元测试、lintDebug、AndroidTest 编译、Debug/Release 构建均通过。
- 设备测试：无可用设备或 AVD，已跳过（SKIPPED_NO_DEVICE）

### 签名与校验
- Debug 签名：CN=Android Debug
- SHA-256：FFFBC24A905681F163468896BE8D130B1D4A703A68D1DFDFD6F00BD249055CC1

---
## v0.6.0 (2026-07-20)

### 功能
- 图片详情改为相册式沉浸浏览：单击显示或隐藏信息和操作层，显示后约 3 秒自动收起。
- 新增双击缩放，可在适配画面与 2.5 倍缩放之间切换；双指缩放和左右切图继续可用。
- AI 描述、标签、图片信息和分析按钮改为仅在底部覆盖层显示，不再通过抽屉遮挡大图。
- 顶部覆盖层展示文件名和拍摄/导入时间；底部展示 AI 摘要、标签、尺寸与模型分析操作。
- README 同步当前 SoIM 架构、Android 10 起的权限策略、AI 配置、搜索能力和发布流程。
- 记录 ColorOS 已选照片选择体验的替代方案：后续将独立设计基于 ACTION_OPEN_DOCUMENT 的持久 URI 导入。

### 修复
- 新增原子 Debug 测试 APK 发布流程，失败时回滚版本元数据与制品。

### 测试
- clean、全量单元测试、lintDebug、AndroidTest 编译、Debug/Release 构建均通过。
- 设备测试：无可用设备或 AVD，已跳过（SKIPPED_NO_DEVICE）

### 签名与校验
- Debug 签名：CN=Android Debug
- SHA-256：F083B684C4F1003AF2F2B618366B8F14B1383D69E925933D912E7FC3C6900A70

---
## v0.5.0 (2026-07-20)

### 功能
- 新增中文 AI 模型设置页，支持供应方、凭据、模型、OpenAI Responses 与声明式自定义 JSON 协议配置。
- 新增全局、供应方、模型三级并发、每分钟请求数和每日请求数限制。
- 新增安全凭据存储、受控 HTTP 传输、图片预处理与严格 canonical AI 输出校验。
- 图片详情新增单图 AI 分析，结果可立即显示并进入图片搜索索引。
- 图片元信息移入原生相册式详细信息面板，不再持续遮挡大图。

### 修复
- 新增原子 Debug 测试 APK 发布流程，失败时回滚版本元数据与制品。

### 测试
- clean、全量单元测试、lintDebug、AndroidTest 编译、Debug/Release 构建均通过。
- 设备测试：无可用设备或 AVD，已跳过（SKIPPED_NO_DEVICE）

### 签名与校验
- Debug 签名：CN=Android Debug
- SHA-256：171207A1F53538FD5A2A5D2F2906E9A6E4C7CEE2D1E625271D2BA4348C50D364

---
## v0.4.1 (2026-07-15)

### 功能
- 图片详情支持按图库稳定顺序左右滑动上一张和下一张，并保留双指缩放。
- 修复首页与图库切换时短暂重放旧索引进度的问题。
- 修复重新扫描缺少反馈，以及完整访问状态下重新选择照片无可见响应的问题。
- 修复已删除图片在校准扫描后仍显示为无图片占位符的问题。
- 记录原生相册式详细信息面板、简单裁切和移动图集的近期迭代方向。

### 修复
- 新增原子 Debug 测试 APK 发布流程，失败时回滚版本元数据与制品。

### 测试
- clean、全量单元测试、lintDebug、AndroidTest 编译、Debug/Release 构建均通过。
- 设备测试：无可用设备或 AVD，已跳过（SKIPPED_NO_DEVICE）

### 签名与校验
- Debug 签名：CN=Android Debug
- SHA-256：A7B1BC21D299FF5C31B4A14692DA7930437E2645FE5261C222204FCBD7BAA5EA

---
## v0.4.0 (2026-07-15)

### 功能
- 发布 SoIM Phase 2A 的本地优先图片管理核心。
- 新增 MediaStore 持久索引、增量同步检查点与同步运行状态。
- 完善媒体权限处理、后台同步以及图库、任务和设置工作区。

### 修复
- 新增原子 Debug 测试 APK 发布流程，失败时回滚版本元数据与制品。

### 测试
- clean、全量单元测试、lintDebug、AndroidTest 编译、Debug/Release 构建均通过。
- 设备测试：无可用设备或 AVD，已跳过（SKIPPED_NO_DEVICE）

### 签名与校验
- Debug 签名：CN=Android Debug
- SHA-256：4F1E5F88C6E32F05996E00B9491042DA4D5E8CEE4A335F36510D9640A7AF2742

---
﻿## v0.3.2 (2026-05-12)

### 新增与修复
- 首页支持两种导入方式：按图集导入（系统图集列表，仅显示图集名称）/按图片导入（PhotoPicker）
- 图集导入后将该图集内图片批量加入 App
- 首页搜索升级：可按文件名、AI描述、AI标签、搜索词匹配图片
- 新增图片详情页：点击图片可放大查看并展示 AI 标注结果（描述、标签、状态）
- AI 标注结果回写到图片模型，支持详情与搜索联动
- 图标资源升级为 v2 资源名，规避部分机型图标缓存不刷新问题

---

# Image AI 鐗堟湰鍙樻洿鏃ュ織

## v0.3.1 (2026-05-12)

### 鏍稿績鍙樻洿

#### 鏉冮檺妯″瀷閲嶆瀯锛堥噸瑕侊級
- **绉婚櫎鎵€鏈夊獟浣撳簱鏉冮檺鐢宠** - 涓嶅啀鐢宠 READ_MEDIA_IMAGES/READ_EXTERNAL_STORAGE
- **鏀圭敤绯荤粺 PhotoPicker** - 鐢ㄦ埛閫氳繃绯荤粺閫夋嫨鍣ㄤ富鍔ㄩ€夋嫨鍥剧墖
- **鏈€灏忔潈闄愬師鍒?* - 搴旂敤浠呭鐞嗙敤鎴锋槑纭€夋嫨鐨勫浘鐗囷紝涓嶆壂鎻忓叏搴?- **闅愮鍙嬪ソ鏂囨** - 鏄庣‘鍛婄煡鐢ㄦ埛"搴旂敤浠呭鐞嗕綘涓诲姩閫夋嫨鐨勭収鐗?

#### 澶氶€塙I閲嶅仛
- **閫夋嫨鎬佷紭鍖?* - 閫変腑鏃跺浘鐗囪交寰缉鏀撅紙0.92锛夛紝鏇夸唬鍗婇€忔槑閬僵
- **鍕鹃€夋爣璁扮簿绠€** - 鍙充笂瑙?0dp灏忓渾鍦?鍕惧彿锛屾浛浠ｅぇ鍙稢heckCircle
- **AI鏍囨敞鎸夐挳** - 鏀逛负搴曢儴绱у噾鍨婼urface锛屾浛浠ｅ法澶х殑FilledIconButton
- **鎿嶄綔鏍忎紭鍖?* - 鍙栨秷/宸查€?鍏ㄩ€変笁鍖哄垎甯冿紝鏇存竻鐖?- **闀挎寜杩涘叆閫夋嫨妯″紡** - 淇濇寔鍘熸湁浜や簰閫昏緫
- **娣诲姞鏇村鍏ュ彛** - 缃戞牸鏈熬"+"鍗＄墖锛屾柟渚胯拷鍔犲浘鐗?
#### API Key娉ㄥ叆
- 浠?`env/apikey.txt` 璇诲彇API Key
- 閫氳繃 BuildConfig 娉ㄥ叆鍒版瀯寤轰骇鐗?- 鍚屾椂鏀寔 `local.properties` 涓殑 `aiApiKey` 灞炴€т綔涓哄閫?
#### 瀵艰埅绠€鍖?- 绉婚櫎鐩稿唽/璇︽儏椤碉紙AlbumScreen/ImageDetailScreen锛?- 涓荤晫闈㈢洿鎺ヤ綔涓哄浘鐗囬€夋嫨鍜屾爣娉ㄥ叆鍙?- 淇濈暀 WebView 鐢ㄤ簬楂樼骇绠＄悊鍔熻兘

### 鎶€鏈粏鑺?- 浣跨敤 `ActivityResultContracts.PickMultipleVisualMedia`
- 鏈€澶氭敮鎸佷竴娆￠€夋嫨20寮犲浘鐗?- AnimatedVisibility 瀹炵幇閫夋嫨妯″紡杩涘叆/閫€鍑哄姩鐢?- itemsIndexed 鐢ㄤ簬甯︾储寮曠殑缃戞牸閬嶅巻

---

## v0.3.0 (2026-05-12)

### 鏂板鍔熻兘

#### 鍘熺敓鐩稿唽UI
- 鐩稿唽鍒楄〃鐣岄潰锛圙alleryScreen锛? 鎸夋枃浠跺す鍒嗙被鏄剧ず鐩稿唽
- 鍥剧墖缃戞牸鐣岄潰锛圓lbumScreen锛? 鏄剧ず鍗曚釜鐩稿唽鍐呯殑鍥剧墖
- 鍥剧墖璇︽儏鐣岄潰锛圛mageDetailScreen锛? 澶у浘娴忚
- 澶氶€夋ā寮忔敮鎸?- 闀挎寜鍥剧墖杩涘叆閫夋嫨妯″紡锛屾敮鎸佸叏閫?
#### 涓婚绯荤粺
- 娴呰壊/鏆楄壊鍙屼富棰橀€傞厤
- 璁剧疆椤甸潰涓婚鍒囨崲锛堣窡闅忕郴缁?娴呰壊/鏆楄壊锛?- DataStore 瀛樺偍鐢ㄦ埛涓婚鍋忓ソ

#### 鍥炬爣涓庡搧鐗?- 浣跨敤 logo.png 浣滀负搴旂敤鍥炬爣
- 鍚勫昂瀵?mipmap 鍥炬爣鑷姩鐢熸垚

#### 鏉冮檺绠＄悊锛堝凡鍦?.3.1搴熷純锛?- ~~READ_MEDIA_IMAGES 鏉冮檺锛圓ndroid 13+锛墌~
- ~~READ_EXTERNAL_STORAGE 鏉冮檺锛圓ndroid 12鍙婁互涓嬶級~~
- ~~鏉冮檺璇锋眰寮曞鐣岄潰~~

### 鎶€鏈粏鑺?- Coil 鍥剧墖鍔犺浇搴撻泦鎴?- Material3 缁勪欢搴?- LazyVerticalGrid 缃戞牸甯冨眬
- Navigation Compose 璺敱閲嶆瀯
- GalleryViewModel/AlbumViewModel 鐘舵€佺鐞?
### 瀵艰埅鍙樻洿
- 榛樿棣栭〉鏀逛负鐩稿唽鍒楄〃锛坓allery锛?- 绉婚櫎鏃?HomeScreen锛堝崟鎸夐挳璺宠浆锛?- 淇濈暀 WebView 椤甸潰鐢ㄤ簬楂樼骇绠＄悊

---

## v0.2.0 (2026-05-12)

### 鏂板鍔熻兘

#### P1 鏁版嵁鍩虹灞?- Room 鏁版嵁搴撻厤缃笌鍒濆鍖?- 7涓狤ntity瀹氫箟锛欼mage, ImageAi, Tag, ImageTag, TagAlias, ImageQueryCache, ImageFeature
- 6涓狣AO鎺ュ彛锛欼mageDao, ImageAiDao, TagDao, ImageTagDao, SearchDao, ImageFeatureDao
- FTS5 鍏ㄦ枃妫€绱㈣櫄鎷熻〃鏀寔

#### P2 鍥剧墖鎺ュ叆灞?- MediaStore 鍥剧墖鎵弿鏈嶅姟
- 鍥剧墖棰勫鐞嗗櫒锛圫HA256璁＄畻銆佸帇缂┿€丒XIF鏃嬭浆绾犳锛?- 鍥剧墖鍏ラ槦绠＄悊锛堝幓閲嶃€佸叆搴擄級

#### P3 AI鍒嗘瀽灞?- OkHttp HTTP瀹㈡埛绔厤缃?- AI API璋冪敤灏佽锛堟敮鎸乂ision妯″瀷锛?- AI缁撴灉澶勭悊鍣紙JSON瑙ｆ瀽銆佹爣绛綾anonical鍖栥€佸琛ㄤ簨鍔″啓鍏ワ級

#### P4 鎼滅储妫€绱㈠眰
- FTS5 鏂囨湰妫€绱㈠疄鐜?- 鐪嬪浘鏌ユ爣绛撅紙搴撳唴鍥剧墖锛?- 鐪嬪浘鏌ユ爣绛撅紙澶栭儴涓存椂鍥剧墖+缂撳瓨锛?
#### P7 JSBridge鎵╁睍
鏂板鏂规硶锛?- `searchImages(query, limit)` - 鏂囨湰鎼滅储鍥剧墖
- `getImageTags(imageId)` - 鑾峰彇鍥剧墖鏍囩
- `saveUserTags(imageId, tagsJson)` - 淇濆瓨鐢ㄦ埛淇鏍囩
- `getAllTags()` - 鑾峰彇鎵€鏈夋爣绛?- `getTagAliases()` - 鑾峰彇鏍囩鍒悕鍒楄〃
- `addTagAlias(alias, canonical)` - 娣诲姞鏍囩鍒悕
- `deleteTagAlias(alias)` - 鍒犻櫎鏍囩鍒悕
- `getStatistics()` - 鑾峰彇鍥剧墖缁熻淇℃伅

#### P8 H5椤甸潰鎵╁睍
- Vue Router 璺敱閰嶇疆
- 棣栭〉瑙嗗浘锛圚omeView锛? 缁熻淇℃伅灞曠ず
- 鎼滅储椤甸潰锛圫earchView锛? 鏂囨湰鎼滅储鍥剧墖
- 鍥剧墖璇︽儏椤碉紙ImageDetailView锛? 鏍囩灞曠ず涓庣敤鎴蜂慨姝?- 鏍囩绠＄悊椤碉紙TagManagerView锛? 鍒悕绠＄悊

### 鍏朵粬鍙樻洿
- 鍖呭悕閲嶆瀯锛歚com.soul2.imageai` 鈫?`cn.soul2.imageai`
- 鏂板渚濊禆锛歊oom, OkHttp, Coroutines, DataStore, Vue Router

---

## v0.1.0 (2026-05-11)

### 鍒濆鐗堟湰
- Android 宸ョ▼楠ㄦ灦锛圞otlin + Compose锛?- H5 宸ョ▼锛圴ue3 + Vant4锛?- WebView 瀹瑰櫒涓庡畨鍏ㄩ厤缃?- JSBridge 鍩虹閫氫俊锛坧ing, getDeviceInfo锛?- Debug/Release APK 鎵撳寘

