import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import path from 'path';

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [vue()],
  root: path.resolve(__dirname, 'frontend'),
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'frontend/src'),
      '@shared': path.resolve(__dirname, 'shared'),
    },
  },
  build: {
    outDir: path.resolve(__dirname, 'web'),
    emptyOutDir: true,
    rollupOptions: {
      output: {
        entryFileNames: `assets/[name]-[hash].js`,
        chunkFileNames: `assets/[name]-[hash].js`,
        assetFileNames: `assets/[name]-[hash].[ext]`
      }
    }
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:18080',
        changeOrigin: true,
      },
      '/ws': {
        target: 'ws://127.0.0.1:18080',
        ws: true,
      },
    },
  },
});
