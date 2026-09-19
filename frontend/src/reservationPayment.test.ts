import { describe, expect, it } from 'vitest'
import { paymentWindow } from './reservationPayment'
import { pathForView, viewFromPath, type View } from './routes'

describe('resuming a held reservation', () => {
  const expiresAt = '2026-10-01T10:10:00Z'
  const deadline = Date.parse(expiresAt)
  it('shows the remaining time without extending the server deadline', () => {
    expect(paymentWindow('PENDING', expiresAt, deadline - 61000)).toEqual({ remainingSeconds: 61, canPay: true, label: '1분 01초' })
    expect(paymentWindow('PENDING', expiresAt, deadline - 1).canPay).toBe(true)
  })
  it('disables payment exactly at the deadline and afterwards', () => {
    expect(paymentWindow('PENDING', expiresAt, deadline).canPay).toBe(false)
    expect(paymentWindow('PENDING', expiresAt, deadline + 1).remainingSeconds).toBe(0)
  })
  it.each(['CONFIRMED', 'CANCELLED', 'EXPIRED', 'FAILED'] as const)('never offers payment for %s', (status) => {
    expect(paymentWindow(status, expiresAt, deadline - 1000).canPay).toBe(false)
  })
  it('does not enable payment for an unreadable deadline', () => {
    expect(paymentWindow('PENDING', 'invalid', deadline).canPay).toBe(false)
  })
})

describe('persistent application routes', () => {
  it.each(['events', 'reservations', 'admin', 'terms', 'privacy'] as View[])('restores %s after navigation or reload', (view) => {
    expect(viewFromPath(pathForView(view))).toBe(view)
  })
  it('retains the catalog fallback for unknown paths', () => {
    expect(viewFromPath('/unknown')).toBe('events')
  })
})
