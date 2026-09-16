import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
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
  // Tailwind v4 serves ONLY the vendored dsh-context tree: its entry sheet
  // (vendor/dsh-context/styles/tailwind.css) disables automatic source
  // detection and @source's its own directory, ships no preflight, and
  // declares the palette subset the dsh cards consume. App code and the
  // other vendored trees stay on CSS modules + --dsw-* tokens.
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@vendor': fileURLToPath(new URL('./vendor', import.meta.url)),
      '@primitives': fileURLToPath(new URL('./vendor/ui-primitives/index.ts', import.meta.url)),
      '@chat-styles': fileURLToPath(new URL('./vendor/chat-styles', import.meta.url)),
      '@dsh-context': fileURLToPath(new URL('./vendor/dsh-context', import.meta.url)),
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
    // Vendor-tree unit tests (the dsh adaptation view models) run in the same
    // suite as app tests; upstream sources themselves stay test-free.
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx', 'vendor/**/*.test.ts', 'vendor/**/*.test.tsx'],
  },
})
