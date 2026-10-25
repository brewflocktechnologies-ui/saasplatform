/// <reference types='vitest' />
import { defineConfig } from 'vite';
import { resolve } from 'node:path';
import { nxReadySignal } from '@nx/vite/plugins/nx-ready-signal.plugin';

export default defineConfig(() => ({
  root: import.meta.dirname,
  cacheDir: '../../node_modules/.vite/apps/chatwidget',
  // Same host port in dev, preview and container mode (http://localhost:5173), matching the CORS allow-list.
  server: { port: 5173, strictPort: true, host: 'localhost' },
  preview: { port: 5173, strictPort: true, host: 'localhost' },
  // Multi-page: / (overview) and /demo (live visitor chat).
  appType: 'mpa',
  resolve: { tsconfigPaths: true },
  plugins: [nxReadySignal()],
  build: {
    outDir: '../../dist/apps/chatwidget',
    emptyOutDir: true,
    reportCompressedSize: true,
    sourcemap: false,
    rollupOptions: {
      input: {
        main: resolve(import.meta.dirname, 'index.html'),
        demo: resolve(import.meta.dirname, 'demo/index.html'),
        embed: resolve(import.meta.dirname, 'src/embed/v1.ts'),
      },
      output: {
        entryFileNames: (chunk: { name: string }) =>
          chunk.name === 'embed' ? 'embed/chat-platform-widget.v1.js' : 'assets/[name]-[hash].js',
      },
    },
  },
  test: {
    name: 'chatwidget',
    watch: false,
    globals: true,
    environment: 'jsdom',
    include: ['src/**/*.{test,spec}.ts'],
    reporters: ['default'],
    coverage: { reportsDirectory: '../../coverage/apps/chatwidget', provider: 'v8' as const },
  },
}));
