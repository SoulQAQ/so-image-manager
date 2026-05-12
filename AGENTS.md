# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Project Overview

A local-first Android image management app with AI-powered tagging and search. Hybrid architecture: native Jetpack Compose + WebView (Vue3 + Vant4 H5 layer).

## Commands

### H5 Development (h5/ directory)
```bash
pnpm install          # Install dependencies
pnpm dev              # Development server
pnpm build:android    # Build for Android (outputs to app/src/main/assets/h5/)
```

### Android Development (project root)
```bash
./gradlew assembleDebug    # Build debug APK
./gradlew assembleRelease  # Build release APK
./gradlew clean            # Clean build
```

Output paths:
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `app/build/outputs/apk/release/`

## Architecture

### Native Layer (Compose)
- Single-Activity architecture with `MainActivity`
- Navigation via `AppNavigation.kt` with routes defined in `NavRoutes.kt`
- Theme system in `ui/theme/`

### H5 Layer (Vue3 + Vant4)
- Built with Vite, outputs to Android assets
- Uses Pinia for state management
- Components from Vant 4 UI library

### WebView Integration
- `WebViewScreen.kt` hosts WebView via `AndroidView`
- Uses `WebViewAssetLoader` to load local assets securely
- URL: `https://appassets.androidplatform.net/assets/h5/index.html`

### JSBridge (Native <-> H5 communication)
- `JsBridge.kt` exposes methods to H5 via `@JavascriptInterface`
- H5 calls via `window.AppBridge.methodName()`
- Unified response format: `{ code: number, message: string, data: object }`
- Current methods: `ping(message)`, `getDeviceInfo()`

### WebView Security (must maintain)
- `setAllowFileAccess(false)`
- `setAllowContentAccess(false)`
- `setAllowFileAccessFromFileURLs(false)`
- `setAllowUniversalAccessFromFileURLs(false)`
- Never load `file://` URLs directly; use WebViewAssetLoader

## Data Layer (Planned)

SQLite with Room, FTS5 for full-text search. Schema includes:
- `image` - file metadata and AI status
- `image_ai` - AI/user tags, caption, categories
- `tag`, `image_tag`, `tag_alias` - tag management
- `image_fts` - FTS5 virtual table for search

## Development Workflow

1. H5 changes: `cd h5 && pnpm build:android` before testing in app
2. Android changes: Build via Gradle, APK goes to `app/build/outputs/apk/`
3. JSBridge additions: Add method in `JsBridge.kt`, expose with `@JavascriptInterface`

## Tech Stack Versions

- Kotlin 2.0.21, Compose BOM 2024.10.01, Material3
- minSdk 26, targetSdk 36, compileSdk 36
- Node.js 20+, pnpm 9+
- Vue 3.5+, Vite 6.x, Vant 4.9+
