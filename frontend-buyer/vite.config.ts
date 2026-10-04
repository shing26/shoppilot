import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

/**
 * 构建产物落点：网关的 static/buyer（round27 票 93）。
 *
 * <p>与工作台同一理由——**浏览器只经网关访问内部服务**（票 15 的纪律），
 * 而买家端的会话与工单都只在网关上对浏览器开着。
 *
 * <p>`base` 用 `/buyer/`：网关的 `/` 是运维调试台、`/workspace/` 是坐席台，三者各占一段路径。
 * 改动 `base` 时要同时改 `AuthFilter` 里的静态资源放行面，否则页面能构建但打不开
 * ——那正是 round23 清场日抓到过的一类洞（`/workspace/` 目录路径没放行 → 401）。
 */
export default defineConfig({
  plugins: [vue()],
  base: '/buyer/',
  build: {
    outDir: fileURLToPath(new URL('../shoppilot-gateway/src/main/resources/static/buyer', import.meta.url)),
    emptyOutDir: true,
    // 产物入库（票 73 的裁定 E），sourcemap 不进仓：它对证据价值为零，却让入库体积翻几倍。
    sourcemap: false,
  },
})