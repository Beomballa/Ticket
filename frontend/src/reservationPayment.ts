import type { ReservationStatus } from './types'

export function paymentWindow(status: ReservationStatus, expiresAt: string, now: number) {
  const deadline = Date.parse(expiresAt)
  const remainingSeconds = Number.isFinite(deadline) ? Math.max(0, Math.ceil((deadline - now) / 1000)) : 0
  return {
    remainingSeconds,
    canPay: status === 'PENDING' && remainingSeconds > 0,
    label: `${Math.floor(remainingSeconds / 60)}분 ${String(remainingSeconds % 60).padStart(2, '0')}초`,
  }
}
