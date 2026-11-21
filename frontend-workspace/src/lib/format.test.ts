import { describe, expect, it } from 'vitest'
import { shortTicketId } from 'frontend-shared'

describe('shortTicketId', () => {
  it('16 位工单号保留首字母与末 8 位', () => {
    expect(shortTicketId('T1795260990650-5')).toBe('T…990650-5')
  })

  it('短号原样返回，不制造无意义的省略号', () => {
    expect(shortTicketId('T-1')).toBe('T-1')
  })
})
