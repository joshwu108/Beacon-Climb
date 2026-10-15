import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    target: 'es2022',
    modulePreload: { polyfill: false },
    rollupOptions: {
      // Fold tiny shared helper chunks into their importers: fewer wrappers and
      // better gzip for the same lazy route boundaries (budget: planning/06 §11).
      output: { experimentalMinChunkSize: 20000 },
    },
  },
  test: {
    // Playwright specs live in e2e/ and run with `pnpm test:e2e`, not Vitest.
    exclude: ['e2e/**', 'node_modules/**', 'dist/**'],
    environment: './src/test/jsdom-environment.ts',
    setupFiles: ['src/test/setup.ts'],
    css: false,
    restoreMocks: true,
  },
});
