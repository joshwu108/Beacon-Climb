import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  test: {
    // Playwright specs live in e2e/ and run with `pnpm test:e2e`, not Vitest.
    exclude: ['e2e/**', 'node_modules/**', 'dist/**'],
    environment: './src/test/jsdom-environment.ts',
    setupFiles: ['src/test/setup.ts'],
    css: false,
    restoreMocks: true,
  },
});
