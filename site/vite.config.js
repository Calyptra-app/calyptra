import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Project page served at https://calyptra-app.github.io/calyptra/
// The base path must match the repo name so assets resolve correctly.
// Override with VITE_BASE=/ for a user/root page or local custom host.
const base = process.env.VITE_BASE ?? '/calyptra/'

// https://vite.dev/config/
export default defineConfig({
  base,
  plugins: [react()],
})
