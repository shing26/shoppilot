import { createApp } from 'vue'
// 设计令牌与基础控件样式（round36 票 151 / ADR 0070）：两个产品入口共用一份。
import 'frontend-shared/tokens.css'
// 买家页的跨组件工具类（round44 票 188）：`.link` / `.pill` / `.mono` 等在拆分后被多个组件共用，
// 各自复制一份迟早分叉，所以提到页级样式里统一引入。
import './styles/base.css'
import App from './App.vue'

createApp(App).mount('#app')