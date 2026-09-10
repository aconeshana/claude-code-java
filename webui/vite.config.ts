import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath } from 'node:url'

// The gateway serves the built bundle at /webui/ (classpath resources), so
// asset URLs must be prefixed with the same base in both dev and build.
const GATEWAY_PORT = process.env.GATEWAY_PORT ?? '8080'

export default defineConfig({
  base: '/webui/',
  // Output one directory deeper so the Gradle srcDir picks the bundle up at
  // the classpath path /webui/** (the gateway's static routes read that
  // prefix); dev still serves at /webui/.
  build: {
    outDir: 'dist/webui',
  },
  plugins: [react()],
  resolve: {
    alias: {
      '@vendor': fileURLToPath(new URL('./vendor', import.meta.url)),
      '@primitives': fileURLToPath(new URL('./vendor/ui-primitives/index.ts', import.meta.url)),
      '@chat-styles': fileURLToPath(new URL('./vendor/chat-styles', import.meta.url)),
    },
  },
  server: {
    proxy: {
      // The gateway sets no CORS headers (loopback-only by design), so dev
      // requests must be proxied to the running TUI's gateway port.
      '/api': { target: `http://127.0.0.1:${GATEWAY_PORT}`, changeOrigin: false },
      '/v1': { target: `http://127.0.0.1:${GATEWAY_PORT}`, changeOrigin: false },
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
  },
})
