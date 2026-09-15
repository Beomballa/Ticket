export type EventStatus = 'DRAFT' | 'PUBLISHED' | 'ON_SALE' | 'CLOSED' | 'CANCELLED'
export type ReservationStatus = 'PENDING' | 'CONFIRMED' | 'CANCELLED' | 'EXPIRED' | 'FAILED'

export interface EventSummary {
  id: number
  title: string
  type: string
  status: EventStatus
  artistId: number
  artistName: string
  salesStartAt: string
  salesEndAt: string
}

export interface InventoryDetail {
  id: number
  type: string
  name: string
  price: number
  totalQuantity: number
  availableQuantity: number
}

export interface EventSessionDetail {
  id: number
  name: string
  venue: string
  startsAt: string
  salesStartAt: string
  salesEndAt: string
  inventory: InventoryDetail[]
}

export interface EventDetail extends EventSummary {
  description: string
  sessions: EventSessionDetail[]
}

export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface MemberProfile {
  id: number
  email: string
  name: string
  role: 'USER' | 'ADMIN'
}

export interface AuthToken {
  accessToken: string
  tokenType: string
  expiresIn: number
}

export interface ReservationResult {
  id: number
  status: ReservationStatus
  totalAmount: number
  expiresAt: string
  items: Array<{
    inventoryId: number
    inventoryName: string
    quantity: number
    unitPrice: number
  }>
}

export interface ReservationSummary {
  reservationId: number
  memberId: number
  memberEmail: string
  status: ReservationStatus
  totalAmount: number
  expiresAt: string
  createdAt: string
  itemCount: number
  totalQuantity: number
}

export interface MemberReservationSummary {
  reservationId: number
  status: ReservationStatus
  totalAmount: number
  expiresAt: string
  createdAt: string
  itemCount: number
  totalQuantity: number
  eventCount: number
  representativeEventTitle: string
}

export interface MemberReservationDetail {
  reservationId: number
  status: ReservationStatus
  totalAmount: number
  expiresAt: string
  confirmedAt: string | null
  cancelledAt: string | null
  expiredAt: string | null
  createdAt: string
  items: Array<{
    itemId: number
    inventoryId: number
    inventoryName: string
    quantity: number
    unitPrice: number
    lineAmount: number
    eventId: number
    eventTitle: string
    eventSessionId: number
    eventSessionName: string
    venue: string
    eventStartsAt: string
  }>
}

export interface InventorySummary {
  inventoryId: number
  type: string
  inventoryName: string
  price: number
  totalQuantity: number
  availableQuantity: number
  reservedQuantity: number
  eventSessionId: number
  eventSessionName: string
  eventStartsAt: string
  eventId: number
  eventTitle: string
}

export interface OperationsSummary {
  totalReservations: number
  confirmedSalesAmount: number
  statusCounts: Partial<Record<ReservationStatus, number>>
}

export interface OutboxEventSummary {
  eventId: string
  aggregateType: string
  aggregateId: string
  eventType: string
  status: 'PENDING' | 'PROCESSING' | 'PUBLISHED' | 'FAILED'
  attempts: number
  availableAt: string
  publishedAt: string | null
  lastError: string | null
  createdAt: string
}

export interface PaymentAttemptSummary {
  paymentAttemptId: string
  reservationId: number
  amount: number
  status: 'UNKNOWN'
  gatewayReference: string | null
  lastError: string | null
  requestedAt: string
  resolvedAt: string | null
  reconciliationAttempts: number
  nextReconciliationAt: string | null
  reconciliationLeaseUntil: string | null
  lastReconciliationAt: string | null
}

export interface RefundAttemptSummary {
  refundAttemptId: string
  reservationId: number
  paymentAttemptId: string
  amount: number
  status: 'UNKNOWN'
  gatewayRefundReference: string | null
  lastError: string | null
  requestedAt: string
  resolvedAt: string | null
  reconciliationAttempts: number
  nextReconciliationAt: string | null
  reconciliationLeaseUntil: string | null
  lastReconciliationAt: string | null
}

export interface ReconcileResult {
  reservationStatus: ReservationStatus
  resolved: boolean
}
