import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

/**
 * 构建产物落点：网关的 static/workspace（round23 票 73）。
 *
 * <p>为什么不是工单服务自己的 static（票面初稿写的是那里）：**浏览器只经网关访问内部服务**——
 * biz-mock 与 ticket 的 InternalAuthFilter 都不接受浏览器直连（票 15 的纪律）。
 * 页面若由 :8092 提供，它去调 :8082 的 ops 端点就变成跨域，于是要么给网关开 CORS
 * （为一个页面放宽整个运维面的跨域策略），要么同源提供。后者更小，所以选它。
 *
 * <p>`emptyOutDir: true` 是必须的：outDir 在项目外时 Vite 默认拒绝清空，而我们要的是
 * 「重建即清干净」——残留的旧 chunk 会让「产物与仓内一致」这条 CI 断言变得没有意义。
 *
 * <p>base 用 `/workspace/`：网关的 `/` 是运维调试台，两者并存各占一段路径。
 */
export default defineConfig({
  plugins: [vue()],
  base: '/workspace/',
  build: {
    outDir: fileURLToPath(new URL('../shoppilot-gateway/src/main/resources/static/workspace', import.meta.url)),
    emptyOutDir: true,
    // 产物入库（所有者裁定 E），所以 sourcemap 不进仓：它对本仓的证据价值为零，
    // 却让入库的静态资源体积翻几倍。
    sourcemap: false,
  },
})