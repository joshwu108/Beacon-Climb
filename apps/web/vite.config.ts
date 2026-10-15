import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: './src/test/jsdom-environment.ts',
    setupFiles: ['src/test/setup.ts'],
    css: false,
    restoreMocks: true,
  },
});
