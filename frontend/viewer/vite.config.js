import { defineConfig } from 'vite';
import path from 'node:path';

const outDir = process.env.VIEWER_OUT_DIR
  ? path.resolve(process.env.VIEWER_OUT_DIR)
  : path.resolve(__dirname, '../../build/generated-resources/viewer/META-INF/resources/viewer');

export default defineConfig({
  base: '/__VIEWER_BASE__/viewer/',
  build: {
    outDir,
    emptyOutDir: true,
    rollupOptions: {
      input: {
        main: path.resolve(__dirname, 'index.html')
      }
    }
  }
});
