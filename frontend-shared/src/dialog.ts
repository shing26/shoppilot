/**
 * 弹层的键盘礼让（round46 票 193 / ADR 0081）：Esc 关闭、Tab 圈在层内、打开时焦点进入、
 * 关闭后还给打开它的控件。
 *
 * <p>**为什么不是 Vue 组合式函数**：`frontend-shared` 没有 `vue` 依赖——两个 app 各自带
 * Vue，这个包只放设计令牌、契约类型与请求封装（round36 票 151）。所以这里做成与框架无关的
 * DOM 函数，弹层在 `onMounted` 里接入、在 `onUnmounted` 里释放，三处共用同一份行为。
 *
 * <p>用 `document` 捕获阶段监听而不是层内监听：焦点一开始可能还在层外的触发按钮上，
 * 层内监听会漏掉第一次 Esc。
 */

/** 可聚焦元素选择器；`:not([tabindex="-1"])` 把显式移出 Tab 序的元素排除掉。 */
const FOCUSABLE = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(', ')

/**
 * 接管一个已挂载的弹层。
 *
 * @param container 弹层根节点（带 `tabindex="-1"`，没有任何可聚焦子元素时焦点落在它上面）
 * @param onClose   按 Esc 时调用；由调用方决定真正关闭，这里不碰 DOM 结构
 * @returns 释放函数：移除监听并把焦点还给打开弹层的控件
 */
export function trapDialogFocus(container: HTMLElement, onClose: () => void): () => void {
  const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null
  const focusables = () =>
    Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE))
      // 隐藏元素（`display:none`、条件渲染残留）不参与循环。
      .filter((element) => element.getClientRects().length > 0)

  const initial = container.querySelector<HTMLElement>('[autofocus]') ?? focusables()[0] ?? container
  initial.focus()

  function onKeydown(event: KeyboardEvent) {
    // 输入法组合中的 Esc 是取消候选，不当作关闭弹层（与 Composer 的回车同一口径）。
    if (event.isComposing) return
    if (event.key === 'Escape') {
      event.preventDefault()
      onClose()
      return
    }
    if (event.key !== 'Tab') return
    const items = focusables()
    if (!items.length) {
      event.preventDefault()
      container.focus()
      return
    }
    const first = items[0]
    const last = items[items.length - 1]
    const active = document.activeElement
    const inside = active instanceof HTMLElement && container.contains(active)
    if (event.shiftKey) {
      if (active === first || !inside) {
        event.preventDefault()
        last.focus()
      }
    } else if (active === last || !inside) {
      event.preventDefault()
      first.focus()
    }
  }

  document.addEventListener('keydown', onKeydown, true)
  return () => {
    document.removeEventListener('keydown', onKeydown, true)
    if (previous?.isConnected) previous.focus()
  }
}
