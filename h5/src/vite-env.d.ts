/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

interface Window {
  AppBridge?: {
    ping(message: string): string
    getDeviceInfo(): string
    searchImages(query: string, limit: number): string
    getImageTags(imageId: number): string
    saveUserTags(imageId: number, tagsJson: string): string
    getAllTags(): string
    getTagAliases(): string
    addTagAlias(alias: string, canonical: string): string
    deleteTagAlias(alias: string): string
    getStatistics(): string
  }
}
