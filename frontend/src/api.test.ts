import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, describeError } from './api'

describe('API errors', () => {
  it('includes a backend trace id in the user-facing error', () => {
    const error = new ApiError('재고가 부족합니다.', 409, 'INSUFFICIENT_STOCK', 'trace-demo-1')
    expect(describeError(error)).toBe('재고가 부족합니다. · 추적 ID trace-demo-1')
  })

  it('falls back to an ordinary error message', () => {
    expect(describeError(new Error('network unavailable'))).toBe('network unavailable')
  })
})

describe('resumed payment request', () => {
  afterEach(() => vi.unstubAllGlobals())
  it('reuses the supplied idempotency key when retrying the same reservation', async () => {
    vi.stubGlobal('localStorage', { getItem: () => null })
    const fetch = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ id: 123, status: 'CONFIRMED' }) })
    vi.stubGlobal('fetch', fetch)
    await api.confirm(123, 'same-payment-attempt')
    await api.confirm(123, 'same-payment-attempt')
    for (const [url, options] of fetch.mock.calls) {
      expect(url).toBe('/api/reservations/123/confirm')
      expect(options.method).toBe('POST')
      expect(options.headers.get('Idempotency-Key')).toBe('same-payment-attempt')
      expect(JSON.parse(options.body)).toEqual({ paymentToken: 'mock-approved' })
    }
  })
})
