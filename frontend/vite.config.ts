import { defineConfig } from 'vitest/config';
import { loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');
  const apiTarget = env.VITE_API_TARGET || 'http://localhost:8081';
  return {
    plugins: [react()],
    server: {
      port: 5173,
      strictPort: true,
      proxy: { '/api': apiTarget, '/share': apiTarget },
    },
    test: { environment: 'jsdom', setupFiles: './src/test/setup.ts' },
  };
});
