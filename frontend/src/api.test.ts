import { describe, expect, it } from 'vitest'
import { ApiError, describeError } from './api'

describe('API errors', () => {
  it('includes a backend trace id in the user-facing error', () => {
    const error = new ApiError('재고가 부족합니다.', 409, 'INSUFFICIENT_STOCK', 'trace-demo-1')
    expect(describeError(error)).toBe('재고가 부족합니다. · 추적 ID trace-demo-1')
  })

  it('falls back to an ordinary error message', () => {
    expect(describeError(new Error('network unavailable'))).toBe('network unavailable')
  })
})
