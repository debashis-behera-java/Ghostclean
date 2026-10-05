import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The backend ships no CORS configuration, so the dev server proxies
// `/api` to the backend origin. Production builds use VITE_API_BASE_URL.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
});
