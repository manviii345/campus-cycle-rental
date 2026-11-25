import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// During development (npm run dev) the Vite server runs on port 5173 and
// proxies API calls and photos to the Java backend on port 8080.
// For the demo you instead run `npm run build` and the backend serves the
// built files itself - only one command to start everything.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/photos': 'http://localhost:8080',
    },
  },
})
