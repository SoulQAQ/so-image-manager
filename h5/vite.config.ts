import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig(({ mode }) => {
  const isAndroid = mode === 'android'

  return {
    plugins: [vue()],
    base: './',
    resolve: {
      alias: {
        '@': resolve(__dirname, 'src')
      }
    },
    build: isAndroid ? {
      outDir: '../app/src/main/assets/h5',
      emptyOutDir: true,
      assetsDir: 'assets'
    } : undefined
  }
})
