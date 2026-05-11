# Image AI - 本地智能图片管理系统

一个**本地优先**的 Android 图片管理 App，支持 AI 自动标注与智能检索。

## 技术栈

### Android
- Kotlin 2.0.21
- Jetpack Compose + Material3
- Navigation Compose
- Room (SQLite + FTS5)
- WebView + JSBridge

### H5
- Vue 3.5+
- Vite 6.x
- Vant 4.9+
- TypeScript
- Pinia

## 项目结构

```
so-image-manager/
├── app/                        # Android 主模块
│   ├── src/main/
│   │   ├── java/com/soul2/imageai/
│   │   │   ├── MainActivity.kt
│   │   │   ├── AppNavigation.kt
│   │   │   ├── ui/
│   │   │   │   ├── theme/
│   │   │   │   ├── navigation/
│   │   │   │   └── screens/
│   │   │   └── webview/
│   │   │       └── JsBridge.kt
│   │   └── assets/h5/          # H5 构建产物
│   └── build.gradle.kts
├── h5/                         # H5 独立工程
│   ├── src/
│   │   ├── App.vue
│   │   └── main.ts
│   ├── vite.config.ts
│   └── package.json
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
└── README.md
```

## 环境要求

### Android 开发
- JDK 17+
- Android SDK Platform 36
- Android Build Tools 36.0.0
- Android Gradle Plugin 8.7.3
- Gradle 8.9+

### H5 开发
- Node.js 20+ (LTS)
- pnpm 9+

## 快速开始

### 1. 克隆项目

```bash
git clone https://github.com/your-repo/so-image-manager.git
cd so-image-manager
```

### 2. H5 开发

```bash
cd h5

# 安装依赖
pnpm install

# 开发模式
pnpm dev

# 构建（输出到 Android assets）
pnpm build:android
```

### 3. Android 开发

```bash
# 在项目根目录

# 构建 Debug APK
./gradlew assembleDebug

# 构建 Release APK
./gradlew assembleRelease

# 清理构建
./gradlew clean
```

### 4. 使用 Android Studio

1. 用 Android Studio 打开项目根目录
2. 等待 Gradle Sync 完成
3. 运行 `app` 模块到模拟器或真机

## JSBridge API

Native 暴露给 H5 的接口：

```javascript
// 测试连接
window.AppBridge.ping(message)
// 返回: { code: 0, message: "Pong: xxx", data: {...} }

// 获取设备信息
window.AppBridge.getDeviceInfo()
// 返回: { code: 0, message: "ok", data: { appName, versionName, ... } }
```

## 开发优先级

### Phase 1（当前）
- [x] Android 工程骨架
- [x] Compose 导航框架
- [x] WebView 容器 + 安全配置
- [x] H5 工程（Vue3 + Vant4）
- [x] 最小 JSBridge

### Phase 2（下一步）
- [ ] SQLite Schema + Room DAO
- [ ] 图片扫描与索引
- [ ] AI 调用与结构化落库
- [ ] FTS5 全文搜索

### Phase 3（远期）
- [ ] Embedding 语义检索
- [ ] 多模态重排
- [ ] 人脸聚类
- [ ] 去重与近重复治理

## 常见问题

### Q: Gradle 构建失败，提示 SDK not found?
A: 确保 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 环境变量指向正确的 Android SDK 目录。或在项目根目录创建 `local.properties`：

```properties
sdk.dir=C:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
```

### Q: WebView 显示空白页?
A: 
1. 确保 H5 已构建：`cd h5 && pnpm build:android`
2. 检查 `app/src/main/assets/h5/index.html` 是否存在
3. 查看 Logcat 中的 WebView 错误信息

### Q: Windows 路径包含中文导致构建失败?
A: 项目已添加 `android.overridePathCheck=true` 到 `gradle.properties`。如仍有问题，建议将项目移动到纯 ASCII 路径。

## License

MIT
