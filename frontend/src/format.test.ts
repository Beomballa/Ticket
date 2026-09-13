import { describe, expect, it } from 'vitest'
import { formatCurrency, statusLabel } from './format'

describe('presentation formatters', () => {
  it('formats Korean won without decimal places', () => {
    expect(formatCurrency(125000)).toBe('₩125,000')
  })

  it('provides Korean labels for operational statuses', () => {
    expect(statusLabel.PENDING).toBe('결제 대기')
    expect(statusLabel.CONFIRMED).toBe('예약 확정')
    expect(statusLabel.EXPIRED).toBe('시간 만료')
  })
})
