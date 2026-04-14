import { defineConfig } from 'vite';
import path from 'node:path';

const outDir = process.env.VIEWER_OUT_DIR
  ? path.resolve(process.env.VIEWER_OUT_DIR)
  : path.resolve(__dirname, '../../build/generated-resources/viewer/META-INF/resources/viewer');

export default defineConfig({
  base: '/viewer/',
  build: {
    outDir,
    emptyOutDir: true,
    rollupOptions: {
      output: {
        entryFileNames: 'assets/[name].js',
        chunkFileNames: 'assets/[name].js',
        assetFileNames: 'assets/[name][extname]'
      }
    }
  }
});
