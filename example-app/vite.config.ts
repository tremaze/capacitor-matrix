import { defineConfig } from 'vite';
import path from 'path';
import wasm from 'vite-plugin-wasm';
import topLevelAwait from 'vite-plugin-top-level-await';

export default defineConfig({
  root: './src',
  build: {
    outDir: '../dist',
    minify: false,
    emptyOutDir: true,
  },
  plugins: [
    wasm(),
    topLevelAwait(),
  ],
  optimizeDeps: {
    exclude: ['@matrix-org/matrix-sdk-crypto-wasm', '@tremaze/capacitor-matrix'],
  },
  server: {
    fs: {
      allow: [
        path.resolve(__dirname, '..'),
      ],
    },
  },
});
