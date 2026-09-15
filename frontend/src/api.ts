import type {
  AuthToken,
  CompensationSummary,
  EventDetail,
  EventSummary,
  InventorySummary,
  MemberReservationDetail,
  MemberReservationSummary,
  MemberProfile,
  OperationsSummary,
  OutboxEventSummary,
  PaymentAttemptSummary,
  Page,
  ReconcileResult,
  RefundAttemptSummary,
  ReservationResult,
  ReservationSummary,
  WebhookInboxSummary,
  WaitingRoomEntry,
  WaitingRoomSummary,
} from './types'

const TOKEN_KEY = 'fan-event.access-token'

interface ErrorBody {
  code?: string
  message?: string
  traceId?: string
}

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string,
    readonly traceId?: string,
  ) {
    super(message)
  }
}

export const authStore = {
  get: () => localStorage.getItem(TOKEN_KEY),
  set: (token: string) => localStorage.setItem(TOKEN_KEY, token),
  clear: () => localStorage.removeItem(TOKEN_KEY),
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = authStore.get()
  const headers = new Headers(init.headers)
  if (init.body) headers.set('Content-Type', 'application/json')
  if (token) headers.set('Authorization', `Bearer ${token}`)

  const response = await fetch(path, { ...init, headers })
  if (!response.ok) {
    const body = (await response.json().catch(() => ({}))) as ErrorBody
    throw new ApiError(
      body.message ?? `요청 처리에 실패했습니다. (${response.status})`,
      response.status,
      body.code ?? 'HTTP_ERROR',
      body.traceId,
    )
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export const api = {
  listEvents: (keyword = '') => {
    const query = new URLSearchParams({ page: '0', size: '24', status: 'ON_SALE' })
    if (keyword.trim()) query.set('keyword', keyword.trim())
    return request<Page<EventSummary>>(`/api/events?${query}`)
  },
  eventDetail: (id: number) => request<EventDetail>(`/api/events/${id}`),
  signup: (email: string, password: string, name: string) =>
    request<MemberProfile>('/api/auth/signup', {
      method: 'POST',
      body: JSON.stringify({ email, password, name }),
    }),
  login: (email: string, password: string) =>
    request<AuthToken>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    }),
  me: () => request<MemberProfile>('/api/members/me'),
  hold: (inventoryId: number, quantity: number, admissionToken?: string, idempotencyKey = crypto.randomUUID()) =>
    request<ReservationResult>('/api/reservations', {
      method: 'POST',
      headers: {
        'Idempotency-Key': idempotencyKey,
        ...(admissionToken ? { 'X-Admission-Token': admissionToken } : {}),
      },
      body: JSON.stringify({ items: [{ inventoryId, quantity }] }),
    }),
  joinWaitingRoom: (eventId: number) => request<WaitingRoomEntry>(
    `/api/events/${eventId}/waiting-room`, { method: 'POST' },
  ),
  waitingRoomStatus: (eventId: number) => request<WaitingRoomEntry>(
    `/api/events/${eventId}/waiting-room`,
  ),
  confirm: (reservationId: number) =>
    request<ReservationResult>(`/api/reservations/${reservationId}/confirm`, {
      method: 'POST',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ paymentToken: 'mock-approved' }),
    }),
  cancel: (reservationId: number) =>
    request<ReservationResult>(`/api/reservations/${reservationId}/cancel`, { method: 'POST' }),
  myReservations: (status = '') => {
    const query = new URLSearchParams({ page: '0', size: '20' })
    if (status) query.set('status', status)
    return request<Page<MemberReservationSummary>>(`/api/reservations?${query}`)
  },
  reservationDetail: (reservationId: number) =>
    request<MemberReservationDetail>(`/api/reservations/${reservationId}`),
  operationsSummary: () => request<OperationsSummary>('/api/admin/reservations/summary'),
  waitingRooms: () => request<WaitingRoomSummary[]>('/api/admin/waiting-rooms'),
  configureWaitingRoom: (
    eventId: number,
    batchSize: number,
    activeCapacity: number,
    admissionTtlSeconds: number,
  ) => request<void>(`/api/admin/events/${eventId}/waiting-room`, {
    method: 'PUT',
    body: JSON.stringify({
      enabled: true,
      batchSize,
      activeCapacity,
      admissionTtl: `PT${admissionTtlSeconds}S`,
    }),
  }),
  closeWaitingRoom: (eventId: number) => request<void>(
    `/api/admin/events/${eventId}/waiting-room`, { method: 'DELETE' },
  ),
  reconcileWaitingRooms: () => request<{ recoveredCount: number }>(
    '/api/admin/waiting-rooms/reconcile', { method: 'POST' },
  ),
  reservations: (status = '') => {
    const query = new URLSearchParams({ page: '0', size: '12' })
    if (status) query.set('status', status)
    return request<Page<ReservationSummary>>(`/api/admin/reservations?${query}`)
  },
  inventory: (soldOut = '') => {
    const query = new URLSearchParams({ page: '0', size: '12' })
    if (soldOut) query.set('soldOut', soldOut)
    return request<Page<InventorySummary>>(`/api/admin/inventory?${query}`)
  },
  exhaustedOutbox: () => request<Page<OutboxEventSummary>>(
    '/api/admin/outbox-events/exhausted?page=0&size=12',
  ),
  retryOutbox: (eventId: string) => request<{ eventId: string; status: string; previousAttempts: number }>(
    `/api/admin/outbox-events/${eventId}/retry`,
    { method: 'POST' },
  ),
  unknownPayments: () => request<Page<PaymentAttemptSummary>>(
    '/api/admin/payment-attempts/unknown?page=0&size=12',
  ),
  reconcilePayment: (paymentAttemptId: string) => request<ReconcileResult>(
    `/api/admin/payment-attempts/${paymentAttemptId}/reconcile`,
    { method: 'POST' },
  ),
  unknownRefunds: () => request<Page<RefundAttemptSummary>>(
    '/api/admin/refund-attempts/unknown?page=0&size=12',
  ),
  reconcileRefund: (refundAttemptId: string) => request<ReconcileResult>(
    `/api/admin/refund-attempts/${refundAttemptId}/reconcile`,
    { method: 'POST' },
  ),
  paymentWebhooks: () => request<Page<WebhookInboxSummary>>(
    '/api/admin/payment-webhooks?page=0&size=12',
  ),
  retryPaymentWebhook: (eventId: string) => request<{
    id: string
    providerEventId: string
    status: WebhookInboxSummary['status']
    duplicate: boolean
  }>(`/api/admin/payment-webhooks/${eventId}/retry`, { method: 'POST' }),
  paymentCompensations: () => request<Page<CompensationSummary>>(
    '/api/admin/payment-compensations?page=0&size=12',
  ),
  retryPaymentCompensation: (attemptId: string) => request<CompensationSummary>(
    `/api/admin/payment-compensations/${attemptId}/retry`,
    { method: 'POST' },
  ),
}

export function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    return error.traceId ? `${error.message} · 추적 ID ${error.traceId}` : error.message
  }
  return error instanceof Error ? error.message : '알 수 없는 오류가 발생했습니다.'
}
