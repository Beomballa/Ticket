export const formatCurrency = (value: number) =>
  new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW', maximumFractionDigits: 0 }).format(value)

export const formatDateTime = (value: string) =>
  new Intl.DateTimeFormat('ko-KR', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))

export const statusLabel: Record<string, string> = {
  PUBLISHED: '공개 예정',
  ON_SALE: '판매 중',
  DRAFT: '준비 중',
  CLOSED: '판매 종료',
  CANCELLED: '취소',
  PENDING: '결제 대기',
  CONFIRMED: '예약 확정',
  EXPIRED: '시간 만료',
  FAILED: '처리 실패',
}
