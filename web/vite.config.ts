/// <reference types="vitest" />
import { fileURLToPath, URL } from "node:url";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// The production build is served by the GEO Tree API itself (backend/app/web.py), so it is
// written straight into backend/web_dist. In development, Vite proxies API calls to the local
// Docker backend: same-origin requests, no CORS configuration needed.
const backend = process.env.GEOTREE_API ?? "http://localhost:8000";

export default defineConfig({
  plugins: [react()],
  resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
  build: {
    outDir: "../backend/web_dist",
    emptyOutDir: true,
    sourcemap: false,
    chunkSizeWarningLimit: 700,
  },
  server: {
    port: 5173,
    proxy: {
      "/api": backend,
      "/uploads": backend,
      "/download": backend,
    },
  },
  test: {
    globals: true,
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    css: false,
  },
});
