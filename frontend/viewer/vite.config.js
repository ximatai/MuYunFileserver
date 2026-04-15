import { defineConfig } from 'vite';
import path from 'node:path';

const outDir = process.env.VIEWER_OUT_DIR
  ? path.resolve(process.env.VIEWER_OUT_DIR)
  : path.resolve(__dirname, '../../build/generated-resources/viewer/META-INF/resources/viewer');

const backendTarget = process.env.VIEWER_DEV_BACKEND || 'http://127.0.0.1:8080';

export default defineConfig(({ command }) => ({
  base: command === 'serve' ? '/' : '/__VIEWER_BASE__/viewer/',
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      '/api': {
        target: backendTarget,
        changeOrigin: true
      },
      '/q': {
        target: backendTarget,
        changeOrigin: true
      }
    }
  },
  build: {
    outDir,
    emptyOutDir: true,
    rollupOptions: {
      input: {
        main: path.resolve(__dirname, 'index.html')
      }
    }
  }
}));
