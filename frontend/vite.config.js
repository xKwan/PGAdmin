import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The SPA is served by the backend from the same origin (plan section 2); the build output is copied into the JAR.
export default defineConfig({
  plugins: [react()],
  base: '/',
  build: {
    outDir: 'dist',
    sourcemap: false,
    // Everything is bundled locally: no CDN, no external URLs (checked by scripts/check-dist.mjs).
    assetsInlineLimit: 0,
  },
  server: {
    port: 5173,
    strictPort: true,
    // Development only: the backend runs on 8080. Negotiate through a proxy is unreliable (plan 7.2), so the
    // backend's test configuration is the recommended way to run the full app locally.
    proxy: { '/api': 'http://localhost:8080' },
  },
  test: {
    environment: 'jsdom',
    globals: false,
    setupFiles: ['./src/test/setup.js'],
    include: ['src/**/*.test.{js,jsx}', 'scripts/**/*.test.js'],
    sequence: { shuffle: true, seed: Number(process.env.PGWEB_TEST_SEED || 0) || undefined },
    coverage: {
      provider: 'v8',
      reporter: ['text-summary', 'lcov', 'json-summary'],
      reportsDirectory: 'coverage',
      include: ['src/**/*.{js,jsx}'],
      exclude: ['src/test/**', 'src/**/*.test.{js,jsx}', 'src/main.jsx', 'src/mocks/**'],
      // Plan section 8.2. Only ever raised.
      thresholds: {
        lines: 80,
        branches: 75,
        functions: 80,
        statements: 80,
        'src/features/{connections,editor,results,transactions}/**': { lines: 85, branches: 80 },
      },
    },
  },
});
