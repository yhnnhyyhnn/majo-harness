import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

const backend = process.env.MAJO_API_TARGET || "http://127.0.0.1:8787";

export default defineConfig({
  plugins: [react()],
  base: "/",
  build: {
    outDir: "dist",
    emptyOutDir: true,
  },
  server: {
    // dev mode reaches the harness backend through this proxy (same origin,
    // so fetch/SSE/plugin assets behave exactly like the packaged server)
    proxy: {
      "/api": { target: backend, changeOrigin: false },
      "/plugins": { target: backend, changeOrigin: false },
    },
  },
});
