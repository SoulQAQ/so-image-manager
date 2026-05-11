import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { resolve } from 'path';
export default defineConfig(function (_a) {
    var mode = _a.mode;
    var isAndroid = mode === 'android';
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
    };
});
