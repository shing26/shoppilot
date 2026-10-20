import { createApp } from 'vue'
// 设计令牌与基础控件样式（round36 票 151 / ADR 0070）：两个产品入口共用一份。
import 'frontend-shared/tokens.css'
import App from './App.vue'

createApp(App).mount('#app')