/// <reference types="vitest" />

import legacy from '@vitejs/plugin-legacy'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [
    react(),
    legacy()
  ],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:3000',
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes, req, res) => {
            // Prevent browser native HTTP auth dialog on 401 responses.
            delete proxyRes.headers['www-authenticate'];
            delete proxyRes.headers['WWW-Authenticate'];
            res.removeHeader('www-authenticate');
            res.removeHeader('WWW-Authenticate');
            res.setHeader('Cache-Control', 'no-cache, no-transform');
          });
        }
      },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/setupTests.ts',
    include: [
      'src/**/*.test.ts',
      'src/**/*.test.tsx',
    ],
    exclude: [
      'e2e/**',
      'cypress/**',
    ],
  }
})
