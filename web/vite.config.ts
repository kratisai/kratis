import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

const testMode = process.env.TEST_MODE;

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    tailwindcss(),
    react(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: true,
    proxy: {
      '/api': {
        changeOrigin: true,
        target: 'http://localhost:8080',
      },
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
      },
    },
  },
  test: {
    chaiConfig: {
      showDiff: testMode !== 'summary'
    },
    coverage: {
      exclude: [
        'node_modules/**',
        'dist/**',
        'tests/**',
        'src/main.tsx',
        'src/vite-env.d.ts'
      ],
      provider: 'v8',
      reporter: ['text', 'json', 'json-summary', 'lcov'],
      thresholds: {
        branches: 75,
        functions: 80,
        lines: 85,
        statements: 85
      },
    },
    environment: 'jsdom',
    globals: true,
    onStackTrace: (_error, frame) => {
      if (testMode === 'summary') return false;
      if (testMode === 'full') return true;
      return !(frame.file.includes('node_modules') || frame.file.startsWith('node:'));
    },
    setupFiles: ['./tests/setup.ts'],
    testTimeout: 15000,
    typecheck: {
      tsconfig: './tsconfig.test.json',
    }
  },
});
