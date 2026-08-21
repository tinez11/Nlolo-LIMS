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
    // The real `server-only` package throws on import outside of Next's build; stub it so
    // modules that import it for the build-time boundary guarantee (see backend.ts) are still
    // importable under jsdom.
    alias: { 'server-only': path.resolve(__dirname, './src/test/server-only-stub.ts') },
  },
  resolve: { alias: { '@': path.resolve(__dirname, './src') } },
});
