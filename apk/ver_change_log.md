# Image AI 版本变更日志

## v0.3.1 (2026-05-12)

### 核心变更

#### 权限模型重构（重要）
- **移除所有媒体库权限申请** - 不再申请 READ_MEDIA_IMAGES/READ_EXTERNAL_STORAGE
- **改用系统 PhotoPicker** - 用户通过系统选择器主动选择图片
- **最小权限原则** - 应用仅处理用户明确选择的图片，不扫描全库
- **隐私友好文案** - 明确告知用户"应用仅处理你主动选择的照片"

#### 多选UI重做
- **选择态优化** - 选中时图片轻微缩放（0.92），替代半透明遮罩
- **勾选标记精简** - 右上角20dp小圆圈+勾号，替代大号CheckCircle
- **AI标注按钮** - 改为底部紧凑型Surface，替代巨大的FilledIconButton
- **操作栏优化** - 取消/已选/全选三区分布，更清爽
- **长按进入选择模式** - 保持原有交互逻辑
- **添加更多入口** - 网格末尾"+"卡片，方便追加图片

#### API Key注入
- 从 `env/apikey.txt` 读取API Key
- 通过 BuildConfig 注入到构建产物
- 同时支持 `local.properties` 中的 `aiApiKey` 属性作为备选

#### 导航简化
- 移除相册/详情页（AlbumScreen/ImageDetailScreen）
- 主界面直接作为图片选择和标注入口
- 保留 WebView 用于高级管理功能

### 技术细节
- 使用 `ActivityResultContracts.PickMultipleVisualMedia`
- 最多支持一次选择20张图片
- AnimatedVisibility 实现选择模式进入/退出动画
- itemsIndexed 用于带索引的网格遍历

---

## v0.3.0 (2026-05-12)

### 新增功能

#### 原生相册UI
- 相册列表界面（GalleryScreen）- 按文件夹分类显示相册
- 图片网格界面（AlbumScreen）- 显示单个相册内的图片
- 图片详情界面（ImageDetailScreen）- 大图浏览
- 多选模式支持 - 长按图片进入选择模式，支持全选

#### 主题系统
- 浅色/暗色双主题适配
- 设置页面主题切换（跟随系统/浅色/暗色）
- DataStore 存储用户主题偏好

#### 图标与品牌
- 使用 logo.png 作为应用图标
- 各尺寸 mipmap 图标自动生成

#### 权限管理（已在0.3.1废弃）
- ~~READ_MEDIA_IMAGES 权限（Android 13+）~~
- ~~READ_EXTERNAL_STORAGE 权限（Android 12及以下）~~
- ~~权限请求引导界面~~

### 技术细节
- Coil 图片加载库集成
- Material3 组件库
- LazyVerticalGrid 网格布局
- Navigation Compose 路由重构
- GalleryViewModel/AlbumViewModel 状态管理

### 导航变更
- 默认首页改为相册列表（gallery）
- 移除旧 HomeScreen（单按钮跳转）
- 保留 WebView 页面用于高级管理

---

## v0.2.0 (2026-05-12)

### 新增功能

#### P1 数据基础层
- Room 数据库配置与初始化
- 7个Entity定义：Image, ImageAi, Tag, ImageTag, TagAlias, ImageQueryCache, ImageFeature
- 6个DAO接口：ImageDao, ImageAiDao, TagDao, ImageTagDao, SearchDao, ImageFeatureDao
- FTS5 全文检索虚拟表支持

#### P2 图片接入层
- MediaStore 图片扫描服务
- 图片预处理器（SHA256计算、压缩、EXIF旋转纠正）
- 图片入队管理（去重、入库）

#### P3 AI分析层
- OkHttp HTTP客户端配置
- AI API调用封装（支持Vision模型）
- AI结果处理器（JSON解析、标签canonical化、多表事务写入）

#### P4 搜索检索层
- FTS5 文本检索实现
- 看图查标签（库内图片）
- 看图查标签（外部临时图片+缓存）

#### P7 JSBridge扩展
新增方法：
- `searchImages(query, limit)` - 文本搜索图片
- `getImageTags(imageId)` - 获取图片标签
- `saveUserTags(imageId, tagsJson)` - 保存用户修正标签
- `getAllTags()` - 获取所有标签
- `getTagAliases()` - 获取标签别名列表
- `addTagAlias(alias, canonical)` - 添加标签别名
- `deleteTagAlias(alias)` - 删除标签别名
- `getStatistics()` - 获取图片统计信息

#### P8 H5页面扩展
- Vue Router 路由配置
- 首页视图（HomeView）- 统计信息展示
- 搜索页面（SearchView）- 文本搜索图片
- 图片详情页（ImageDetailView）- 标签展示与用户修正
- 标签管理页（TagManagerView）- 别名管理

### 其他变更
- 包名重构：`com.soul2.imageai` → `cn.soul2.imageai`
- 新增依赖：Room, OkHttp, Coroutines, DataStore, Vue Router

---

## v0.1.0 (2026-05-11)

### 初始版本
- Android 工程骨架（Kotlin + Compose）
- H5 工程（Vue3 + Vant4）
- WebView 容器与安全配置
- JSBridge 基础通信（ping, getDeviceInfo）
- Debug/Release APK 打包
