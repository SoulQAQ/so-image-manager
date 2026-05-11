/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

interface Window {
  AppBridge?: {
    ping: (message: string) => { code: number; message: string; data: any }
  }
}
