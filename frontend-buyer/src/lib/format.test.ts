import { describe, expect, it } from 'vitest'
import { renderMarkdown } from './format'

describe('renderMarkdown', () => {
  it('把粗体与行内代码渲染成固定标签', () => {
    expect(renderMarkdown('**责任判定**：以 `ruleId` 为准')).toBe(
      '<p><strong>责任判定</strong>：以 <code>ruleId</code> 为准</p>',
    )
  })

  it('先转义 HTML，再插入标签', () => {
    const html = renderMarkdown('<img src=x onerror=alert(1)> **安全**')
    expect(html).not.toContain('<img')
    expect(html).toContain('&lt;img')
    expect(html).toContain('<strong>安全</strong>')
  })

  it('把连续的无序列表收进一个 ul', () => {
    expect(renderMarkdown('- 活鲜：6 小时\n* 冷藏：24 小时')).toBe(
      '<ul><li>活鲜：6 小时</li><li>冷藏：24 小时</li></ul>',
    )
  })

  it('支持单星号斜体', () => {
    expect(renderMarkdown('*超时将无法受理。*')).toBe('<p><em>超时将无法受理。</em></p>')
  })

  it('有序步骤保留原始序号', () => {
    expect(renderMarkdown('1. 核验订单\n2. 提交理赔')).toBe(
      '<ol><li value="1">核验订单</li><li value="2">提交理赔</li></ol>',
    )
  })

  it('空行分段，未闭合的粗体原样保留', () => {
    expect(renderMarkdown('第一段\n\n**还没闭合')).toBe(
      '<p>第一段</p><p>**还没闭合</p>',
    )
  })
})
