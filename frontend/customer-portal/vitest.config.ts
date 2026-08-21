import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'node:path';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./vitest.setup.ts'],
    globals: true,
    include: ['src/**/*.test.{ts,tsx}'],
    // Default 'forks' pool times out spawning workers in this sandboxed
    // environment (Windows dev container); 'threads' is reliable here.
    pool: 'threads',
  },
  resolve: { alias: { '@': path.resolve(__dirname, './src') } },
});
