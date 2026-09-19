import { useEffect, useState } from 'react'
import { paymentWindow } from './reservationPayment'
import { formatDateTime } from './format'
import type { ReservationStatus } from './types'

export function usePaymentWindow(status: ReservationStatus, expiresAt: string) {
  const [now, setNow] = useState(Date.now)
  const payment = paymentWindow(status, expiresAt, now)
  useEffect(() => {
    setNow(Date.now())
    if (status !== 'PENDING') return
    const interval = globalThis.setInterval(() => setNow(Date.now()), 1000)
    return () => globalThis.clearInterval(interval)
  }, [status, expiresAt])
  return payment
}

export function PaymentDeadline({ expiresAt, remainingSeconds, label }: { expiresAt: string; remainingSeconds: number; label: string }) {
  return <div className="payment-deadline">
    {remainingSeconds > 0 ? <>
      <span>결제까지 남은 시간</span><strong role="timer" aria-live="off">{label}</strong>
      <small>{formatDateTime(expiresAt)}까지 · 실제 청구 없는 모의 결제</small>
    </> : <p role="status">결제 시간이 지났습니다. 최신 상태를 확인한 뒤 필요한 경우 다시 예매해 주세요.</p>}
  </div>
}
