import { FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, api, authStore, describeError } from './api'
import { formatCurrency, formatDateTime, statusLabel } from './format'
import { CatalogBrowser } from './CatalogBrowser'
import { eventArtwork, genreLabel, saleState } from './catalog'
import { useDialog } from './useDialog'
import { pathForView, viewFromPath, type View } from './routes'
import { PaymentDeadline, usePaymentWindow } from './PaymentDeadline'
import type {
  CompensationSummary,
  EventDetail,
  InventorySummary,
  MemberProfile,
  MemberReservationDetail,
  MemberReservationSummary,
  OperationsSummary,
  OutboxEventSummary,
  PaymentAttemptSummary,
  RefundAttemptSummary,
  ReservationResult,
  ReservationStatus,
  ReservationSummary,
  WebhookInboxSummary,
  WaitingRoomEntry,
  WaitingRoomSummary,
} from './types'

function App() {
  const [view, setView] = useState<View>(() => viewFromPath(window.location.pathname))
  const [member, setMember] = useState<MemberProfile | null>(null)
  const [authOpen, setAuthOpen] = useState(false)
  const [notice, setNotice] = useState('')

  useEffect(() => {
    if (!authStore.get()) return
    api.me()
      .then(setMember)
      .catch(() => authStore.clear())
  }, [])

  useEffect(() => {
    const handlePopState = () => setView(viewFromPath(window.location.pathname))
    window.addEventListener('popstate', handlePopState)
    return () => window.removeEventListener('popstate', handlePopState)
  }, [])

  const navigate = (nextView: View) => {
    setView(nextView)
    const path = pathForView(nextView)
    if (window.location.pathname !== path) window.history.pushState({}, '', path)
    window.scrollTo({ top: 0 })
  }

  const logout = () => {
    authStore.clear()
    setMember(null)
    navigate('events')
    setNotice('로그아웃했습니다.')
  }

  return (
    <div className="app-shell">
      <header className="site-header">
        <button className="brand" onClick={() => navigate('events')} aria-label="이벤트 홈">
          <span className="brand-mark">S</span>
          <span>StagePass</span>
        </button>
        <nav aria-label="주요 메뉴">
          <button className={view === 'events' ? 'nav-active' : ''} onClick={() => navigate('events')}>
            이벤트
          </button>
          <button className={view === 'reservations' ? 'nav-active' : ''} onClick={() => navigate('reservations')}>
            내 예약
          </button>
          <button className={view === 'admin' ? 'nav-active' : ''} onClick={() => navigate('admin')}>
            운영 콘솔
          </button>
        </nav>
        <div className="account-actions">
          {member ? (
            <>
              <span className="member-chip">{member.name} · {member.role}</span>
              <button className="button ghost small" onClick={logout}>로그아웃</button>
            </>
          ) : (
            <button className="button primary small" onClick={() => setAuthOpen(true)}>로그인</button>
          )}
        </div>
      </header>

      {notice && <div className="toast" role="status" onAnimationEnd={() => setNotice('')}>{notice}</div>}

      <main>
        {view === 'events' ? (
          <EventCatalog member={member} onLogin={() => setAuthOpen(true)} onNotice={setNotice} />
        ) : view === 'reservations' ? (
          <MyReservations member={member} onLogin={() => setAuthOpen(true)} onNotice={setNotice} />
        ) : view === 'admin' ? (
          <AdminConsole member={member} onLogin={() => setAuthOpen(true)} />
        ) : (
          <LegalPage kind={view} />
        )}
      </main>

      <footer>
        <div>
          <strong>StagePass</strong>
          <span>한정 수량 공연·팬 이벤트 예약 포트폴리오</span>
        </div>
        <div className="footer-links">
          <a href="/terms" onClick={(event) => { event.preventDefault(); navigate('terms') }}>이용약관</a>
          <a href="/privacy" onClick={(event) => { event.preventDefault(); navigate('privacy') }}>개인정보처리방침</a>
        </div>
      </footer>

      {authOpen && (
        <AuthDialog
          onClose={() => setAuthOpen(false)}
          onAuthenticated={(profile) => {
            setMember(profile)
            setAuthOpen(false)
            setNotice(`${profile.name}님, 반갑습니다.`)
          }}
        />
      )}
    </div>
  )
}

function EventCatalog({
  member,
  onLogin,
  onNotice,
}: {
  member: MemberProfile | null
  onLogin: () => void
  onNotice: (message: string) => void
}) {
  const [selected, setSelected] = useState<EventDetail | null>(null)

  return (
    <>
      <CatalogBrowser onSelect={setSelected} />

      {selected && (
        <EventDrawer
          key={selected.id}
          event={selected}
          member={member}
          onClose={() => setSelected(null)}
          onLogin={onLogin}
          onChanged={async () => {
            setSelected(await api.eventDetail(selected.id))
            onNotice('최신 재고를 반영했습니다.')
          }}
        />
      )}
    </>
  )
}

function EventDrawer({
  event,
  member,
  onClose,
  onLogin,
  onChanged,
}: {
  event: EventDetail
  member: MemberProfile | null
  onClose: () => void
  onLogin: () => void
  onChanged: () => Promise<void>
}) {
  const [reservation, setReservation] = useState<ReservationResult | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [waitingRoom, setWaitingRoom] = useState<WaitingRoomEntry | null>(null)
  const [queuedInventoryId, setQueuedInventoryId] = useState<number | null>(null)
  const reservationKey = useRef(crypto.randomUUID())
  const dialogRef = useDialog(onClose)
  const [sessionId, setSessionId] = useState(event.sessions[0]?.id)
  const selectedSession = event.sessions.find((session) => session.id === sessionId)
  const poster = eventArtwork(event)
  const sale = saleState(event)
  const sessionSale = selectedSession ? saleState({ ...selectedSession, status: event.status }) : sale

  const run = async (operation: () => Promise<ReservationResult>) => {
    setPending(true)
    setError('')
    try {
      setReservation(await operation())
      await onChanged()
    } catch (requestError) {
      if (requestError instanceof ApiError && requestError.status === 401) onLogin()
      setError(describeError(requestError))
    } finally {
      setPending(false)
    }
  }

  const reserve = async (inventoryId: number, refresh = false) => {
    if (!member) return onLogin()
    setPending(true)
    setError('')
    try {
      if (!waitingRoom || queuedInventoryId !== inventoryId) reservationKey.current = crypto.randomUUID()
      const entry = refresh
        ? await api.waitingRoomStatus(event.id)
        : await api.joinWaitingRoom(event.id)
      setWaitingRoom(entry)
      setQueuedInventoryId(inventoryId)
      if (entry.status === 'WAITING') return
      setReservation(await api.hold(inventoryId, 1, entry.admissionToken ?? undefined, reservationKey.current))
      setWaitingRoom(null)
      reservationKey.current = crypto.randomUUID()
      await onChanged()
    } catch (requestError) {
      if (requestError instanceof ApiError && requestError.status === 401) onLogin()
      setError(describeError(requestError))
    } finally {
      setPending(false)
    }
  }

  return (
    <div className="overlay" role="presentation" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <aside ref={dialogRef} className="drawer event-drawer" role="dialog" aria-modal="true" aria-labelledby="event-title">
        <button className="icon-button close" onClick={onClose} aria-label="닫기">×</button>
        <div className="drawer-category">{genreLabel(event.type)} · 공연 상세</div>
        <div className="drawer-body">
          <div className="event-detail-intro">
            {poster && <img className="detail-poster" src={poster.src} alt={`${event.title} 데모 포스터`} width="640" height="960" />}
            <div>
              <p className="eyebrow">{event.artistName}</p>
              <h2 id="event-title">{event.title}</h2>
              <span className={`status ${sale.available ? 'open' : 'pending'}`}>{sale.label}</span>
            </div>
          </div>
          <dl className="event-sale-dates">
            <div><dt>판매 시작</dt><dd>{formatDateTime(event.salesStartAt)}</dd></div>
            <div><dt>판매 마감</dt><dd>{formatDateTime(event.salesEndAt)}</dd></div>
          </dl>
          <section className="event-description" aria-labelledby="event-description-heading">
            <h3 id="event-description-heading">공연 소개</h3>
            <p>{event.description}</p>
          </section>
          {error && <ErrorPanel message={error} />}
          {waitingRoom?.status === 'WAITING' && (
            <section className="waiting-room-card" role="status">
              <div><p className="eyebrow">WAITING ROOM</p><h3>{waitingRoom.position ? `${waitingRoom.position}번째로 기다리는 중` : '입장 순서를 확인하는 중'}</h3></div>
              <p>예상 대기 {waitingRoom.estimatedWaitSeconds ?? 0}초 · 순번은 처음 참가한 시점 그대로 유지됩니다.</p>
              <button className="button primary" disabled={pending || queuedInventoryId === null} onClick={() => queuedInventoryId && void reserve(queuedInventoryId, true)}>{pending ? '확인 중…' : '입장 상태 새로고침'}</button>
            </section>
          )}

          {reservation ? (
            <ReservationPanel reservation={reservation} pending={pending} onConfirm={() => run(() => api.confirm(reservation.id))} onCancel={() => run(() => api.cancel(reservation.id))} />
          ) : (
            <section className="session-list" aria-label="회차와 입장권 선택">
              <label className="session-picker">관람 회차 선택
                <select value={sessionId ?? ''} onChange={(e) => setSessionId(Number(e.target.value))} disabled={pending || waitingRoom?.status === 'WAITING'}>
                  {event.sessions.map((session) => <option key={session.id} value={session.id}>{formatDateTime(session.startsAt)} · {session.name}</option>)}
                </select>
              </label>
              {!event.sessions.length && <p className="muted">회차가 아직 등록되지 않았습니다.</p>}
              <p className="ticket-note">비지정석 입장권 · 1회 1매 선점 · 선점 후 10분 이내 모의 결제</p>
              {(selectedSession ? [selectedSession] : []).map((session) => (
                <section className="session" key={session.id}>
                  <div className="session-title"><div><strong>{session.name}</strong><p>{session.venue} · {formatDateTime(session.startsAt)}</p></div></div>
                  {session.inventory.map((stock) => (
                    <div className="stock-row" key={stock.id}>
                      <div><strong>{stock.name}</strong><p>{formatCurrency(stock.price)} · 잔여 {stock.availableQuantity}</p></div>
                      <button
                        className="button primary small"
                        disabled={pending || waitingRoom?.status === 'WAITING' || stock.availableQuantity === 0 || !sale.available || !sessionSale.available}
                        onClick={() => void reserve(stock.id)}
                      >{stock.availableQuantity === 0 ? '매진' : !sale.available ? sale.label : !sessionSale.available ? sessionSale.label : '1매 예약'}</button>
                    </div>
                  ))}
                </section>
              ))}
            </section>
          )}
        </div>
      </aside>
    </div>
  )
}

function ReservationPanel({ reservation, pending, onConfirm, onCancel }: { reservation: ReservationResult; pending: boolean; onConfirm: () => void; onCancel: () => void }) {
  const actionable = reservation.status === 'PENDING' || reservation.status === 'CONFIRMED'
  const payment = usePaymentWindow(reservation.status, reservation.expiresAt)
  return (
    <section className="reservation-panel">
      <span className={`status ${reservation.status.toLowerCase()}`}>{statusLabel[reservation.status]}</span>
      <h3>예약 #{reservation.id}</h3>
      {reservation.items.map((item) => <p key={item.inventoryId}>{item.inventoryName} × {item.quantity}</p>)}
      <strong className="reservation-total">{formatCurrency(reservation.totalAmount)}</strong>
      {reservation.status === 'PENDING' && <><PaymentDeadline expiresAt={reservation.expiresAt} {...payment} /><small>창을 닫아도 내 예약에서 결제를 이어갈 수 있습니다.</small></>}
      {actionable && (
        <div className="button-row">
          {reservation.status === 'PENDING' && <button className="button primary" disabled={pending || !payment.canPay} onClick={onConfirm}>모의 결제 확정</button>}
          <button className="button ghost" disabled={pending} onClick={onCancel}>예약 취소</button>
        </div>
      )}
    </section>
  )
}

function AuthDialog({ onClose, onAuthenticated }: { onClose: () => void; onAuthenticated: (member: MemberProfile) => void }) {
  const dialogRef = useDialog(onClose)
  const [signup, setSignup] = useState(false)
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setPending(true)
    setError('')
    try {
      if (signup) await api.signup(email, password, name)
      const token = await api.login(email, password)
      authStore.set(token.accessToken)
      onAuthenticated(await api.me())
    } catch (requestError) {
      setError(describeError(requestError))
    } finally {
      setPending(false)
    }
  }

  return (
    <div className="overlay auth-overlay" role="presentation">
      <section ref={dialogRef} className="auth-card" role="dialog" aria-modal="true" aria-labelledby="auth-title">
        <button className="icon-button close" onClick={onClose} aria-label="닫기">×</button>
        <p className="eyebrow">STAGEPASS ACCOUNT</p>
        <h2 id="auth-title">{signup ? '새 계정 만들기' : '다시 만나 반가워요'}</h2>
        <p className="muted">예약 선점과 확정에는 회원 인증이 필요합니다.</p>
        <form onSubmit={(event) => void submit(event)}>
          {signup && <label>이름<input required maxLength={100} value={name} onChange={(e) => setName(e.target.value)} /></label>}
          <label>이메일<input required type="email" value={email} onChange={(e) => setEmail(e.target.value)} /></label>
          <label>비밀번호<input required type="password" minLength={8} maxLength={72} value={password} onChange={(e) => setPassword(e.target.value)} /></label>
          {error && <ErrorPanel message={error} />}
          <button className="button primary full" disabled={pending} type="submit">{pending ? '처리 중…' : signup ? '가입하고 로그인' : '로그인'}</button>
        </form>
        <button className="text-button" onClick={() => { setSignup(!signup); setError('') }}>{signup ? '이미 계정이 있어요' : '처음이라면 회원가입'}</button>
      </section>
    </div>
  )
}

function MyReservations({
  member,
  onLogin,
  onNotice,
}: {
  member: MemberProfile | null
  onLogin: () => void
  onNotice: (message: string) => void
}) {
  const [reservations, setReservations] = useState<MemberReservationSummary[]>([])
  const [selected, setSelected] = useState<MemberReservationDetail | null>(null)
  const headingRef = useRef<HTMLHeadingElement>(null)
  const [status, setStatus] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    if (!member) return
    setLoading(true)
    setError('')
    try {
      setReservations((await api.myReservations(status)).content)
    } catch (requestError) {
      setError(describeError(requestError))
    } finally {
      setLoading(false)
    }
  }, [member, status])

  useEffect(() => { void load() }, [load])

  const open = async (reservationId: number) => {
    setError('')
    try {
      setSelected(await api.reservationDetail(reservationId))
    } catch (requestError) {
      setError(describeError(requestError))
    }
  }

  if (!member) return <AccessState title="내 예약은 로그인이 필요합니다" description="본인의 예약 내역을 안전하게 확인하려면 먼저 로그인해 주세요." action="로그인" onAction={onLogin} />

  return (
    <section className="reservation-page">
      <div className="admin-heading">
        <div><p className="eyebrow">MY STAGEPASS</p><h1 ref={headingRef} tabIndex={-1}>내 예약</h1><p className="muted">선점부터 확정·취소·만료까지 예약 상태를 확인합니다.</p></div>
        <Filter value={status} onChange={setStatus} options={[["", "전체 상태"], ["PENDING", "결제 대기"], ["CONFIRMED", "확정"], ["CANCELLED", "취소"], ["EXPIRED", "만료"], ["FAILED", "처리 실패"]]} />
      </div>
      {error && <ErrorPanel message={error} retry={load} />}
      {loading && reservations.length === 0 ? <InlineLoading /> : reservations.length === 0 ? (
        <EmptyState title="예약 내역이 없습니다" description="판매 중인 이벤트에서 첫 예약을 만들어 보세요." />
      ) : (
        <div className="reservation-history" aria-busy={loading}>
          {reservations.map((item) => (
            <button className="reservation-history-card" key={item.reservationId} onClick={() => void open(item.reservationId)}>
              <div>
                <span className={`status ${item.status.toLowerCase()}`}>{statusLabel[item.status]}</span>
                <h2>{item.representativeEventTitle}{item.eventCount > 1 ? ` 외 ${item.eventCount - 1}개 이벤트` : ''}</h2>
                <p>예약 #{item.reservationId} · {item.itemCount}개 항목 · 총 {item.totalQuantity}개</p>
              </div>
              <div className="reservation-history-meta"><strong>{formatCurrency(item.totalAmount)}</strong><time>{formatDateTime(item.createdAt)}</time></div>
            </button>
          ))}
        </div>
      )}
      {selected && (
        <ReservationDetailDrawer
          key={selected.reservationId}
          reservation={selected}
          onLogin={onLogin}
          onClose={() => { setSelected(null); headingRef.current?.focus() }}
          onRefresh={async () => {
            const detail = await api.reservationDetail(selected.reservationId)
            setSelected((current) => current?.reservationId === detail.reservationId ? detail : current)
            await load()
          }}
          onConfirm={async (idempotencyKey) => {
            const result = await api.confirm(selected.reservationId, idempotencyKey)
            setSelected((current) => current?.reservationId === result.id ? { ...current, status: result.status } : current)
            onNotice('모의 결제를 확정했습니다. 실제 금액은 청구되지 않습니다.')
            await load()
          }}
          onCancel={async () => {
            const result = await api.cancel(selected.reservationId)
            setSelected((current) => current?.reservationId === result.id ? { ...current, status: result.status } : current)
            await load()
            onNotice('예약을 취소하고 재고를 반환했습니다.')
          }}
        />
      )}
    </section>
  )
}

function ReservationDetailDrawer({
  reservation,
  onClose,
  onLogin,
  onCancel,
  onConfirm,
  onRefresh,
}: {
  reservation: MemberReservationDetail
  onClose: () => void
  onLogin: () => void
  onCancel: () => Promise<void>
  onConfirm: (idempotencyKey: string) => Promise<void>
  onRefresh: () => Promise<void>
}) {
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const cancellable = reservation.status === 'PENDING' || reservation.status === 'CONFIRMED'
  const dialogRef = useDialog(onClose)
  const confirmationKey = useRef(crypto.randomUUID())
  const inFlight = useRef(false)
  const payment = usePaymentWindow(reservation.status, reservation.expiresAt)

  const run = async (operation: () => Promise<void>) => {
    if (inFlight.current) return
    inFlight.current = true
    setPending(true)
    setError('')
    try { await operation() }
    catch (requestError) {
      if (requestError instanceof ApiError && requestError.status === 401) onLogin()
      setError(`${describeError(requestError)} · 최신 상태를 확인해 주세요.`)
    }
    finally { inFlight.current = false; setPending(false) }
  }

  return (
    <div className="overlay" role="presentation" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <aside ref={dialogRef} className="drawer" role="dialog" aria-modal="true" aria-labelledby="reservation-detail-title">
        <button className="icon-button close" onClick={onClose} aria-label="닫기">×</button>
        <div className="drawer-body reservation-detail">
          <p className="eyebrow">RESERVATION DETAIL</p>
          <h2 id="reservation-detail-title">예약 #{reservation.reservationId}</h2>
          <span className={`status ${reservation.status.toLowerCase()}`}>{statusLabel[reservation.status]}</span>
          <p className="muted">{formatDateTime(reservation.createdAt)} 생성 · {reservation.items.length}개 항목</p>
          {error && <ErrorPanel message={error} />}
          <div className="reservation-detail-items">
            {reservation.items.map((item) => (
              <article key={item.itemId}>
                <div><strong>{item.eventTitle}</strong><p>{item.eventSessionName} · {item.venue}</p><time>{formatDateTime(item.eventStartsAt)}</time></div>
                <div><strong>{item.inventoryName} × {item.quantity}</strong><p>{formatCurrency(item.lineAmount)}</p></div>
              </article>
            ))}
          </div>
          <div className="reservation-detail-total"><span>총 결제 금액</span><strong>{formatCurrency(reservation.totalAmount)}</strong></div>
          {reservation.status === 'PENDING' && <PaymentDeadline expiresAt={reservation.expiresAt} {...payment} />}
          <div className="reservation-actions" aria-busy={pending}>
            {reservation.status === 'PENDING' && <button className="button primary full" disabled={pending || !payment.canPay} onClick={() => void run(() => onConfirm(confirmationKey.current))}>모의 결제 이어하기</button>}
            {cancellable && <button className="button ghost full" disabled={pending} onClick={() => void run(onCancel)}>예약 취소</button>}
            <button className="button ghost full" disabled={pending} onClick={() => void run(onRefresh)}>최신 상태 확인</button>
          </div>
          {pending && <p role="status" className="muted">요청을 처리하고 있습니다.</p>}
        </div>
      </aside>
    </div>
  )
}

function AdminConsole({ member, onLogin }: { member: MemberProfile | null; onLogin: () => void }) {
  const [summary, setSummary] = useState<OperationsSummary | null>(null)
  const [reservations, setReservations] = useState<ReservationSummary[]>([])
  const [inventory, setInventory] = useState<InventorySummary[]>([])
  const [outbox, setOutbox] = useState<OutboxEventSummary[]>([])
  const [payments, setPayments] = useState<PaymentAttemptSummary[]>([])
  const [refunds, setRefunds] = useState<RefundAttemptSummary[]>([])
  const [webhooks, setWebhooks] = useState<WebhookInboxSummary[]>([])
  const [compensations, setCompensations] = useState<CompensationSummary[]>([])
  const [waitingRooms, setWaitingRooms] = useState<WaitingRoomSummary[]>([])
  const [paymentTotal, setPaymentTotal] = useState(0)
  const [refundTotal, setRefundTotal] = useState(0)
  const [webhookTotal, setWebhookTotal] = useState(0)
  const [compensationTotal, setCompensationTotal] = useState(0)
  const [reconciling, setReconciling] = useState('')
  const [actionNotice, setActionNotice] = useState('')
  const [reservationStatus, setReservationStatus] = useState('')
  const [soldOut, setSoldOut] = useState('')
  const [policyEventId, setPolicyEventId] = useState('')
  const [policyBatchSize, setPolicyBatchSize] = useState('50')
  const [policyCapacity, setPolicyCapacity] = useState('200')
  const [policyTtlSeconds, setPolicyTtlSeconds] = useState('120')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    if (member?.role !== 'ADMIN') return
    setLoading(true)
    setError('')
    try {
      const [nextSummary, nextReservations, nextInventory, nextOutbox, nextPayments, nextRefunds, nextWebhooks, nextCompensations, nextWaitingRooms] = await Promise.all([
        api.operationsSummary(), api.reservations(reservationStatus), api.inventory(soldOut), api.exhaustedOutbox(),
        api.unknownPayments(), api.unknownRefunds(), api.paymentWebhooks(), api.paymentCompensations(), api.waitingRooms(),
      ])
      setSummary(nextSummary)
      setReservations(nextReservations.content)
      setInventory(nextInventory.content)
      setOutbox(nextOutbox.content)
      setPayments(nextPayments.content)
      setRefunds(nextRefunds.content)
      setWebhooks(nextWebhooks.content)
      setCompensations(nextCompensations.content)
      setWaitingRooms(nextWaitingRooms)
      setPaymentTotal(nextPayments.totalElements)
      setRefundTotal(nextRefunds.totalElements)
      setWebhookTotal(nextWebhooks.totalElements)
      setCompensationTotal(nextCompensations.totalElements)
    } catch (requestError) {
      setError(describeError(requestError))
    } finally {
      setLoading(false)
    }
  }, [member?.role, reservationStatus, soldOut])

  useEffect(() => { void load() }, [load])

  const reconcile = async (type: 'payment' | 'refund', attemptId: string) => {
    setReconciling(attemptId)
    setError('')
    setActionNotice('')
    try {
      const result = type === 'payment'
        ? await api.reconcilePayment(attemptId)
        : await api.reconcileRefund(attemptId)
      setActionNotice(result.resolved
        ? `대사가 완료되었습니다. 예약 상태: ${statusLabel[result.reservationStatus]}`
        : 'PG 결과가 아직 확인되지 않아 자동 재시도를 유지합니다.')
      await load()
    } catch (requestError) {
      setError(describeError(requestError))
    } finally {
      setReconciling('')
    }
  }

  const retryWebhook = async (eventId: string) => {
    setReconciling(eventId)
    setError('')
    setActionNotice('')
    try {
      const result = await api.retryPaymentWebhook(eventId)
      setActionNotice(result.status === 'PROCESSED'
        ? 'PG 웹훅 재처리가 완료되었습니다.'
        : '웹훅을 다시 확인했지만 처리 실패 상태가 유지됩니다.')
      await load()
    } catch (requestError) {
      setError(describeError(requestError))
    } finally {
      setReconciling('')
    }
  }

  const retryCompensation = async (attemptId: string) => {
    setReconciling(attemptId)
    setError('')
    setActionNotice('')
    try {
      const result = await api.retryPaymentCompensation(attemptId)
      setActionNotice(result.status === 'SUCCEEDED'
        ? '늦은 승인 보상 환불이 완료되었습니다.'
        : `보상 환불 상태가 ${compensationStatusLabel[result.status]}(으)로 갱신되었습니다.`)
      await load()
    } catch (requestError) {
      setError(describeError(requestError))
    } finally {
      setReconciling('')
    }
  }

  const saveWaitingRoom = async (formEvent: FormEvent) => {
    formEvent.preventDefault()
    setLoading(true)
    setError('')
    setActionNotice('')
    try {
      await api.configureWaitingRoom(
        Number(policyEventId), Number(policyBatchSize), Number(policyCapacity), Number(policyTtlSeconds),
      )
      setActionNotice(`이벤트 #${policyEventId} 대기열 정책을 저장했습니다.`)
      await load()
    } catch (requestError) {
      setError(describeError(requestError))
    } finally {
      setLoading(false)
    }
  }

  const closeWaitingRoom = async (eventId: number) => {
    setLoading(true)
    setError('')
    try {
      await api.closeWaitingRoom(eventId)
      setActionNotice(`이벤트 #${eventId} 대기열을 중지했습니다.`)
      await load()
    } catch (requestError) {
      setError(describeError(requestError))
    } finally {
      setLoading(false)
    }
  }

  const reconcileWaitingRooms = async () => {
    setLoading(true)
    setError('')
    try {
      const result = await api.reconcileWaitingRooms()
      setActionNotice(`Redis 대기열 ${result.recoveredCount}개를 복구했습니다.`)
      await load()
    } catch (requestError) {
      setError(describeError(requestError))
    } finally {
      setLoading(false)
    }
  }

  if (!member) return <AccessState title="운영 데이터는 로그인이 필요합니다" action="로그인" onAction={onLogin} />
  if (member.role !== 'ADMIN') return <AccessState title="ADMIN 권한이 필요한 화면입니다" action="이벤트로 돌아가기" onAction={() => window.scrollTo({ top: 0 })} />

  return (
    <section className="admin-page">
      <div className="admin-heading">
        <div><p className="eyebrow">OPERATIONS</p><h1>예약 운영 콘솔</h1><p className="muted">예약 상태와 한정 재고를 같은 기준으로 확인합니다.</p></div>
        <button className="button dark" disabled={loading} onClick={() => void load()}>새로고침</button>
      </div>

      <section className="table-card waiting-room-admin-card">
        <div className="table-heading"><div><h2>실시간 예매 대기열</h2><p>PostgreSQL 정책 · Redis 원자 입장 · 최근 1분 처리량</p></div><button className="button ghost small" disabled={loading} onClick={() => void reconcileWaitingRooms()}>Redis 상태 복구</button></div>
        <form className="waiting-room-policy-form" onSubmit={(event) => void saveWaitingRoom(event)}>
          <label>이벤트 ID<input type="number" min="1" required value={policyEventId} onChange={(event) => setPolicyEventId(event.target.value)} /></label>
          <label>배치 크기<input type="number" min="1" max="10000" required value={policyBatchSize} onChange={(event) => setPolicyBatchSize(event.target.value)} /></label>
          <label>활성 정원<input type="number" min="1" max="100000" required value={policyCapacity} onChange={(event) => setPolicyCapacity(event.target.value)} /></label>
          <label>토큰 TTL(초)<input type="number" min="10" max="3600" required value={policyTtlSeconds} onChange={(event) => setPolicyTtlSeconds(event.target.value)} /></label>
          <button className="button primary" disabled={loading}>활성화·저장</button>
        </form>
        {loading ? <InlineLoading /> : waitingRooms.length === 0 ? <EmptyState title="운영 중인 대기열이 없습니다" description="대기열을 연 인기 이벤트가 여기에 표시됩니다." /> : (
          <div className="table-scroll"><table><thead><tr><th>이벤트</th><th>정책</th><th>대기</th><th>입장 활성</th><th>최근 1분</th><th>Redis</th><th /></tr></thead><tbody>
            {waitingRooms.map((room) => <tr key={room.eventId}><td><strong>{room.eventTitle}</strong><br /><small>#{room.eventId}</small></td><td>배치 {room.batchSize} · 정원 {room.activeCapacity}<br /><small>TTL {room.admissionTtlSeconds}초</small></td><td>{room.waitingCount}명</td><td>{room.admittedCount}명</td><td>{room.admittedLastMinute}명</td><td><span className={`status ${room.redisStatus === 'SYNCHRONIZED' ? 'succeeded' : room.redisStatus === 'UNAVAILABLE' ? 'failed' : 'pending'}`}>{room.redisStatus}</span></td><td><div className="compact-actions"><button className="button ghost small" onClick={() => { setPolicyEventId(String(room.eventId)); setPolicyBatchSize(String(room.batchSize)); setPolicyCapacity(String(room.activeCapacity)); setPolicyTtlSeconds(String(room.admissionTtlSeconds)) }}>수정</button>{room.enabled && <button className="button ghost small" onClick={() => void closeWaitingRoom(room.eventId)}>중지</button>}</div></td></tr>)}
          </tbody></table></div>
        )}
      </section>
      {error && <ErrorPanel message={error} retry={load} />}
      {actionNotice && <div className="action-notice" role="status">{actionNotice}</div>}
      <div className="metric-grid">
        <Metric label="전체 예약" value={summary?.totalReservations ?? 0} />
        <Metric label="확정 매출" value={formatCurrency(summary?.confirmedSalesAmount ?? 0)} />
        <Metric label="결제 대기" value={summary?.statusCounts.PENDING ?? 0} tone="warning" />
        <Metric label="예약 확정" value={summary?.statusCounts.CONFIRMED ?? 0} tone="success" />
      </div>

      <div className="admin-grid">
        <section className="table-card">
          <div className="table-heading"><div><h2>최근 예약</h2><p>고정 정렬 · QueryDSL projection</p></div><Filter value={reservationStatus} onChange={setReservationStatus} options={[['', '전체 상태'], ['PENDING', '결제 대기'], ['CONFIRMED', '확정'], ['CANCELLED', '취소'], ['EXPIRED', '만료'], ['FAILED', '처리 실패']]} /></div>
          {loading ? <InlineLoading /> : reservations.length === 0 ? <EmptyState title="예약이 없습니다" description="선택한 조건에 맞는 예약이 없습니다." /> : (
            <div className="table-scroll"><table><thead><tr><th>예약</th><th>회원</th><th>상태</th><th>금액</th><th>생성</th></tr></thead><tbody>
              {reservations.map((item) => <tr key={item.reservationId}><td>#{item.reservationId}</td><td>{item.memberEmail}</td><td><span className={`status ${item.status.toLowerCase()}`}>{statusLabel[item.status]}</span></td><td>{formatCurrency(item.totalAmount)}</td><td>{formatDateTime(item.createdAt)}</td></tr>)}
            </tbody></table></div>
          )}
        </section>

        <section className="table-card">
          <div className="table-heading"><div><h2>재고 현황</h2><p>잔여·선점 수량 계산</p></div><Filter value={soldOut} onChange={setSoldOut} options={[['', '전체 재고'], ['true', '매진'], ['false', '판매 가능']]} /></div>
          {loading ? <InlineLoading /> : inventory.length === 0 ? <EmptyState title="재고가 없습니다" description="선택한 조건에 맞는 재고가 없습니다." /> : (
            <div className="inventory-list">{inventory.map((item) => {
              const rate = item.totalQuantity ? Math.round((item.availableQuantity / item.totalQuantity) * 100) : 0
              return <article key={item.inventoryId} className="inventory-item"><div><strong>{item.eventTitle}</strong><p>{item.eventSessionName} · {item.inventoryName}</p></div><div className="stock-meter"><span><i style={{ width: `${rate}%` }} /></span><small>{item.availableQuantity} / {item.totalQuantity}</small></div></article>
            })}</div>
          )}
        </section>
      </div>

      <section className="table-card outbox-card">
        <div className="table-heading"><div><h2>재처리 대기 Outbox</h2><p>최대 시도 횟수에 도달한 실패 이벤트만 표시합니다.</p></div><span className="status failed">{outbox.length}건</span></div>
        {loading ? <InlineLoading /> : outbox.length === 0 ? <EmptyState title="격리된 이벤트가 없습니다" description="자동 재시도 한도를 넘은 실패 이벤트가 없습니다." /> : (
          <div className="table-scroll"><table><thead><tr><th>이벤트</th><th>대상</th><th>시도</th><th>마지막 오류</th><th>생성</th><th /></tr></thead><tbody>
            {outbox.map((item) => <tr key={item.eventId}><td>{item.eventType}</td><td>{item.aggregateType} #{item.aggregateId}</td><td>{item.attempts}</td><td className="error-cell">{item.lastError}</td><td>{formatDateTime(item.createdAt)}</td><td><button className="button ghost small" onClick={async () => { try { await api.retryOutbox(item.eventId); await load() } catch (requestError) { setError(describeError(requestError)) } }}>재처리</button></td></tr>)}
          </tbody></table></div>
        )}
      </section>

      <section className="table-card webhook-card">
        <div className="table-heading"><div><h2>PG 웹훅 Inbox</h2><p>서명 검증을 통과한 결제·환불 결과와 멱등 처리 상태입니다.</p></div><span className="status received">{webhookTotal}건</span></div>
        {loading ? <InlineLoading /> : webhooks.length === 0 ? <EmptyState title="수신한 웹훅이 없습니다" description="PG 결과가 도착하면 최근 내역이 여기에 표시됩니다." /> : (
          <div className="table-scroll"><table><thead><tr><th>PG 이벤트</th><th>구분</th><th>결과</th><th>상태</th><th>시도</th><th>수신</th><th>마지막 오류</th><th /></tr></thead><tbody>
            {webhooks.map((item) => <tr key={item.id} title={item.providerEventId}><td className="event-id-cell">{item.providerEventId}</td><td>{item.eventType === 'REFUND_RESULT' ? '환불' : '결제 승인'}</td><td>{item.result}</td><td><span className={`status ${item.status.toLowerCase()}`}>{webhookStatusLabel[item.status]}</span></td><td>{item.attempts}회</td><td>{formatDateTime(item.receivedAt)}</td><td className="error-cell" title={item.lastError ?? undefined}>{item.lastError ?? '—'}</td><td>{item.status === 'FAILED' && <button className="button ghost small" disabled={reconciling === item.id} onClick={() => void retryWebhook(item.id)}>{reconciling === item.id ? '처리 중…' : '재처리'}</button>}</td></tr>)}
          </tbody></table></div>
        )}
      </section>

      <section className="table-card compensation-card">
        <div className="table-heading"><div><h2>늦은 승인 보상 환불</h2><p>만료 후 확인된 결제 승인을 예약 복원 없이 자동 환불합니다.</p></div><span className="status compensation">{compensationTotal}건</span></div>
        {loading ? <InlineLoading /> : compensations.length === 0 ? <EmptyState title="보상 환불이 없습니다" description="만료 이후 결제가 승인된 예외 사건이 없습니다." /> : (
          <div className="table-scroll"><table><thead><tr><th>예약</th><th>금액</th><th>상태</th><th>시도</th><th>발생</th><th>다음 실행</th><th>마지막 오류</th><th /></tr></thead><tbody>
            {compensations.map((item) => <tr key={item.refundAttemptId}><td>#{item.reservationId}</td><td>{formatCurrency(item.amount)}</td><td><span className={`status ${item.status.toLowerCase()}`}>{compensationStatusLabel[item.status]}</span></td><td>{item.attempts}회</td><td>{formatDateTime(item.requestedAt)}</td><td>{item.nextReconciliationAt ? formatDateTime(item.nextReconciliationAt) : '—'}</td><td className="error-cell" title={item.lastError ?? undefined}>{item.lastError ?? '—'}</td><td>{item.status !== 'SUCCEEDED' && <button className="button ghost small" disabled={reconciling === item.refundAttemptId} onClick={() => void retryCompensation(item.refundAttemptId)}>{reconciling === item.refundAttemptId ? '처리 중…' : '재처리'}</button>}</td></tr>)}
          </tbody></table></div>
        )}
      </section>

      <div className="reconciliation-grid">
        <ReconciliationTable
          title="결제 결과 불명"
          description="자동 대사 대상과 다음 재시도 시각"
          total={paymentTotal}
          loading={loading}
          rows={payments.map((item) => ({
            id: item.paymentAttemptId,
            reservationId: item.reservationId,
            amount: item.amount,
            attempts: item.reconciliationAttempts,
            requestedAt: item.requestedAt,
            nextAt: item.nextReconciliationAt,
            leased: Boolean(item.reconciliationLeaseUntil),
            error: item.lastError,
          }))}
          reconciling={reconciling}
          onReconcile={(id) => reconcile('payment', id)}
        />
        <ReconciliationTable
          title="환불 결과 불명"
          description="환불 성공 확인 전 재고를 계속 선점합니다."
          total={refundTotal}
          loading={loading}
          rows={refunds.map((item) => ({
            id: item.refundAttemptId,
            reservationId: item.reservationId,
            amount: item.amount,
            attempts: item.reconciliationAttempts,
            requestedAt: item.requestedAt,
            nextAt: item.nextReconciliationAt,
            leased: Boolean(item.reconciliationLeaseUntil),
            error: item.lastError,
          }))}
          reconciling={reconciling}
          onReconcile={(id) => reconcile('refund', id)}
        />
      </div>
    </section>
  )
}

const webhookStatusLabel: Record<WebhookInboxSummary['status'], string> = {
  RECEIVED: '수신',
  PROCESSING: '처리 중',
  PROCESSED: '완료',
  FAILED: '실패',
}

const compensationStatusLabel: Record<CompensationSummary['status'], string> = {
  REQUESTED: '환불 대기',
  UNKNOWN: '결과 확인 중',
  SUCCEEDED: '환불 완료',
  DECLINED: '환불 거절',
}

interface ReconciliationRow {
  id: string
  reservationId: number
  amount: number
  attempts: number
  requestedAt: string
  nextAt: string | null
  leased: boolean
  error: string | null
}

function ReconciliationTable({
  title, description, total, loading, rows, reconciling, onReconcile,
}: {
  title: string
  description: string
  total: number
  loading: boolean
  rows: ReconciliationRow[]
  reconciling: string
  onReconcile: (id: string) => Promise<void>
}) {
  return (
    <section className="table-card">
      <div className="table-heading"><div><h2>{title}</h2><p>{description}</p></div><span className="status unknown">{total}건</span></div>
      {loading ? <InlineLoading /> : rows.length === 0 ? <EmptyState title="대사 대상이 없습니다" description="모든 PG 결과가 확정된 상태입니다." /> : (
        <div className="table-scroll"><table><thead><tr><th>예약</th><th>금액</th><th>발생</th><th>자동 시도</th><th>다음 실행</th><th>상태</th><th /></tr></thead><tbody>
          {rows.map((item) => <tr key={item.id} title={item.error ?? undefined}><td>#{item.reservationId}</td><td>{formatCurrency(item.amount)}</td><td>{formatDateTime(item.requestedAt)}</td><td>{item.attempts}회</td><td>{item.nextAt ? formatDateTime(item.nextAt) : '대기'}</td><td><span className={`status ${item.leased ? 'processing' : 'unknown'}`}>{item.leased ? '처리 중' : '대기'}</span></td><td><button className="button ghost small" disabled={reconciling === item.id} onClick={() => void onReconcile(item.id)}>{reconciling === item.id ? '확인 중…' : '지금 대사'}</button></td></tr>)}
        </tbody></table></div>
      )}
    </section>
  )
}

function LegalPage({ kind }: { kind: 'terms' | 'privacy' }) {
  const isTerms = kind === 'terms'

  return (
    <article className="legal-page">
      <header className="legal-heading">
        <p className="eyebrow">STAGEPASS POLICY</p>
        <h1>{isTerms ? '이용약관' : '개인정보처리방침'}</h1>
        <p>{isTerms
          ? 'StagePass 데모 서비스의 계정, 예매와 취소 이용 기준을 안내합니다.'
          : '회원가입과 예매 과정에서 처리되는 정보를 현재 구현 기준으로 안내합니다.'}</p>
        <div className="policy-notice" role="note">
          이 문서는 포트폴리오 데모 기준 초안입니다. 운영 주체, 연락처, 시행일과 법정 보유기간은 실제 공개 전에 입력 및 검토가 필요합니다.
        </div>
      </header>

      {isTerms ? (
        <div className="legal-sections">
          <section><h2>1. 서비스 목적</h2><p>StagePass는 공연·팬 이벤트를 조회하고 한정 재고를 선점해 모의 결제까지 경험할 수 있는 포트폴리오 서비스입니다. 실제 유료 결제나 실물 티켓 발권은 제공하지 않습니다.</p></section>
          <section><h2>2. 계정 이용</h2><p>회원은 본인의 이름과 이메일로 계정을 만들 수 있으며, 계정 접근 정보가 노출되지 않도록 관리해야 합니다. 운영 콘솔은 관리자 권한이 있는 계정만 이용할 수 있습니다.</p></section>
          <section><h2>3. 예약과 결제 확정</h2><p>예약 요청이 성공하면 재고가 일정 시간 선점되고 예약은 결제 대기 상태가 됩니다. 표시된 만료 시각 안에 모의 결제를 확정하지 않으면 예약이 만료되고 재고가 반환됩니다.</p></section>
          <section><h2>4. 취소와 환불</h2><p>결제 대기 예약은 취소 즉시 재고가 반환됩니다. 확정 예약은 모의 환불 결과가 성공한 경우에 취소와 재고 반환이 완료됩니다.</p></section>
          <section><h2>5. 서비스 제한</h2><p>재고 부족, 대기열 미입장, 중복 요청 처리 중 또는 시스템 점검 상황에서는 예약 요청이 제한될 수 있습니다. 동일 요청의 반복 처리를 막기 위해 멱등 요청 기준을 적용합니다.</p></section>
          <section><h2>6. 운영 정보</h2><dl><div><dt>운영 주체</dt><dd>[입력 필요]</dd></div><div><dt>주소 및 연락처</dt><dd>[입력 필요]</dd></div><div><dt>시행일</dt><dd>[입력 필요]</dd></div></dl></section>
        </div>
      ) : (
        <div className="legal-sections">
          <section><h2>1. 처리하는 정보</h2><p>회원가입 시 이름, 이메일과 비밀번호를 처리합니다. 비밀번호는 원문이 아닌 해시로 저장됩니다. 예약 이용 시 회원 식별자, 예약 항목, 수량, 가격 스냅샷, 상태와 처리 시각을 저장합니다.</p></section>
          <section><h2>2. 처리 목적</h2><p>계정 인증, 본인 예약 조회, 한정 재고 선점, 예약 확정·취소·만료 처리와 운영 장애 추적을 위해 정보를 사용합니다.</p></section>
          <section><h2>3. 결제 관련 정보</h2><p>현재 서비스는 모의 결제만 제공합니다. 결제 토큰 원문은 저장하지 않고 fingerprint와 처리 상태, 멱등키 및 모의 PG 참조 정보를 기록합니다.</p></section>
          <section><h2>4. 보유 및 삭제</h2><p>계정과 예약 데이터의 실제 보유기간, 회원 탈퇴 및 삭제 절차는 아직 정해지지 않았습니다. 공개 운영 전에 법적 근거와 서비스 정책에 맞춰 확정해야 합니다.</p></section>
          <section><h2>5. 처리 시스템</h2><p>회원·이벤트·예약·결제 처리 기록은 PostgreSQL에 저장되며, 예매 대기열과 짧은 수명의 입장 상태는 Redis에서 처리됩니다. 운영 환경의 외부 제공자와 국외 이전 여부는 현재 확정되지 않았습니다.</p></section>
          <section><h2>6. 담당 정보</h2><dl><div><dt>개인정보 처리 주체</dt><dd>[입력 필요]</dd></div><div><dt>문의 연락처</dt><dd>[입력 필요]</dd></div><div><dt>시행일 및 보유기간</dt><dd>[입력 필요]</dd></div></dl></section>
        </div>
      )}
    </article>
  )
}

function Filter({ value, onChange, options }: { value: string; onChange: (value: string) => void; options: Array<[string, string]> }) {
  return <select value={value} onChange={(event) => onChange(event.target.value)}>{options.map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select>
}

function Metric({ label, value, tone = '' }: { label: string; value: string | number; tone?: string }) {
  return <article className={`metric ${tone}`}><span>{label}</span><strong>{value}</strong></article>
}

function ErrorPanel({ message, retry }: { message: string; retry?: () => void | Promise<void> }) {
  return <div className="error-panel" role="alert"><span>{message}</span>{retry && <button onClick={() => void retry()}>다시 시도</button>}</div>
}

function EmptyState({ title, description }: { title: string; description: string }) {
  return <div className="empty-state"><strong>{title}</strong><p>{description}</p></div>
}

function AccessState({ title, description = '운영 화면은 관리자 계정으로 이용할 수 있습니다.', action, onAction }: { title: string; description?: string; action: string; onAction: () => void }) {
  return <section className="access-state"><h1>{title}</h1><p>{description}</p><button className="button primary" onClick={onAction}>{action}</button></section>
}

function InlineLoading() {
  return <div className="inline-loading"><span />데이터를 불러오는 중입니다.</div>
}

export default App
