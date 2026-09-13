import { FormEvent, useCallback, useEffect, useState } from 'react'
import { ApiError, api, authStore, describeError } from './api'
import { formatCurrency, formatDateTime, statusLabel } from './format'
import type {
  EventDetail,
  EventSummary,
  InventorySummary,
  MemberProfile,
  OperationsSummary,
  ReservationResult,
  ReservationStatus,
  ReservationSummary,
} from './types'

type View = 'events' | 'admin'

function App() {
  const [view, setView] = useState<View>('events')
  const [member, setMember] = useState<MemberProfile | null>(null)
  const [authOpen, setAuthOpen] = useState(false)
  const [notice, setNotice] = useState('')

  useEffect(() => {
    if (!authStore.get()) return
    api.me()
      .then(setMember)
      .catch(() => authStore.clear())
  }, [])

  const logout = () => {
    authStore.clear()
    setMember(null)
    setView('events')
    setNotice('로그아웃했습니다.')
  }

  return (
    <div className="app-shell">
      <header className="site-header">
        <button className="brand" onClick={() => setView('events')} aria-label="이벤트 홈">
          <span className="brand-mark">S</span>
          <span>StagePass</span>
        </button>
        <nav aria-label="주요 메뉴">
          <button className={view === 'events' ? 'nav-active' : ''} onClick={() => setView('events')}>
            이벤트
          </button>
          <button className={view === 'admin' ? 'nav-active' : ''} onClick={() => setView('admin')}>
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
        ) : (
          <AdminConsole member={member} onLogin={() => setAuthOpen(true)} />
        )}
      </main>

      <footer>
        <span>StagePass portfolio</span>
        <span>JPA · QueryDSL · Redis · Outbox · Observability</span>
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
  const [events, setEvents] = useState<EventSummary[]>([])
  const [selected, setSelected] = useState<EventDetail | null>(null)
  const [keyword, setKeyword] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const loadEvents = useCallback(async (query = '') => {
    setLoading(true)
    setError('')
    try {
      setEvents((await api.listEvents(query)).content)
    } catch (requestError) {
      setError(describeError(requestError))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void loadEvents() }, [loadEvents])

  const openEvent = async (id: number) => {
    setError('')
    try {
      setSelected(await api.eventDetail(id))
    } catch (requestError) {
      setError(describeError(requestError))
    }
  }

  return (
    <>
      <section className="hero">
        <div>
          <p className="eyebrow">FAN EVENT RESERVATION</p>
          <h1>좋아하는 순간을<br />놓치지 마세요.</h1>
          <p className="hero-copy">한정 수량 이벤트를 찾고, 재고 선점부터 결제 확정까지 한 흐름으로 경험합니다.</p>
        </div>
        <div className="hero-stat" aria-label="서비스 기술 특징">
          <span>동시성 안전 재고</span>
          <strong>Atomic</strong>
          <small>조건부 UPDATE와 멱등 요청</small>
        </div>
      </section>

      <section className="content-section">
        <div className="section-heading">
          <div><p className="eyebrow">NOW OPEN</p><h2>판매 중인 이벤트</h2></div>
          <form className="search" onSubmit={(event) => { event.preventDefault(); void loadEvents(keyword) }}>
            <label className="sr-only" htmlFor="event-search">이벤트 검색</label>
            <input id="event-search" value={keyword} onChange={(event) => setKeyword(event.target.value)} placeholder="아티스트 또는 이벤트" />
            <button className="button dark" type="submit">검색</button>
          </form>
        </div>

        {error ? <ErrorPanel message={error} retry={() => loadEvents(keyword)} /> : loading ? <CardSkeletons /> : events.length === 0 ? (
          <EmptyState title="판매 중인 이벤트가 없습니다" description="검색어를 바꾸거나 관리자에서 이벤트를 공개해 주세요." />
        ) : (
          <div className="event-grid">
            {events.map((event) => (
              <button className="event-card" key={event.id} onClick={() => void openEvent(event.id)}>
                <div className={`event-art art-${event.id % 4}`}><span>{event.type}</span></div>
                <div className="event-info">
                  <span className="status open">{statusLabel[event.status]}</span>
                  <h3>{event.title}</h3>
                  <p>{event.artistName}</p>
                  <time>{formatDateTime(event.salesEndAt)} 마감</time>
                </div>
              </button>
            ))}
          </div>
        )}
      </section>

      {selected && (
        <EventDrawer
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

  return (
    <div className="overlay" role="presentation" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <aside className="drawer" role="dialog" aria-modal="true" aria-labelledby="event-title">
        <button className="icon-button close" onClick={onClose} aria-label="닫기">×</button>
        <div className={`drawer-art art-${event.id % 4}`}><span>{event.type}</span></div>
        <div className="drawer-body">
          <p className="eyebrow">{event.artistName}</p>
          <h2 id="event-title">{event.title}</h2>
          <p className="muted">{event.description}</p>
          {error && <ErrorPanel message={error} />}

          {reservation ? (
            <ReservationPanel reservation={reservation} pending={pending} onConfirm={() => run(() => api.confirm(reservation.id))} onCancel={() => run(() => api.cancel(reservation.id))} />
          ) : (
            <div className="session-list">
              {event.sessions.map((session) => (
                <section className="session" key={session.id}>
                  <div className="session-title"><div><strong>{session.name}</strong><p>{session.venue} · {formatDateTime(session.startsAt)}</p></div></div>
                  {session.inventory.map((stock) => (
                    <div className="stock-row" key={stock.id}>
                      <div><strong>{stock.name}</strong><p>{formatCurrency(stock.price)} · 잔여 {stock.availableQuantity}</p></div>
                      <button
                        className="button primary small"
                        disabled={pending || stock.availableQuantity === 0}
                        onClick={() => member ? run(() => api.hold(stock.id, 1)) : onLogin()}
                      >{stock.availableQuantity === 0 ? '매진' : '1매 예약'}</button>
                    </div>
                  ))}
                </section>
              ))}
            </div>
          )}
        </div>
      </aside>
    </div>
  )
}

function ReservationPanel({ reservation, pending, onConfirm, onCancel }: { reservation: ReservationResult; pending: boolean; onConfirm: () => void; onCancel: () => void }) {
  const actionable = reservation.status === 'PENDING' || reservation.status === 'CONFIRMED'
  return (
    <section className="reservation-panel">
      <span className={`status ${reservation.status.toLowerCase()}`}>{statusLabel[reservation.status]}</span>
      <h3>예약 #{reservation.id}</h3>
      {reservation.items.map((item) => <p key={item.inventoryId}>{item.inventoryName} × {item.quantity}</p>)}
      <strong className="reservation-total">{formatCurrency(reservation.totalAmount)}</strong>
      {reservation.status === 'PENDING' && <small>{formatDateTime(reservation.expiresAt)}까지 결제</small>}
      {actionable && (
        <div className="button-row">
          {reservation.status === 'PENDING' && <button className="button primary" disabled={pending} onClick={onConfirm}>모의 결제 확정</button>}
          <button className="button ghost" disabled={pending} onClick={onCancel}>예약 취소</button>
        </div>
      )}
    </section>
  )
}

function AuthDialog({ onClose, onAuthenticated }: { onClose: () => void; onAuthenticated: (member: MemberProfile) => void }) {
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
      <section className="auth-card" role="dialog" aria-modal="true" aria-labelledby="auth-title">
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

function AdminConsole({ member, onLogin }: { member: MemberProfile | null; onLogin: () => void }) {
  const [summary, setSummary] = useState<OperationsSummary | null>(null)
  const [reservations, setReservations] = useState<ReservationSummary[]>([])
  const [inventory, setInventory] = useState<InventorySummary[]>([])
  const [reservationStatus, setReservationStatus] = useState('')
  const [soldOut, setSoldOut] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    if (member?.role !== 'ADMIN') return
    setLoading(true)
    setError('')
    try {
      const [nextSummary, nextReservations, nextInventory] = await Promise.all([
        api.operationsSummary(), api.reservations(reservationStatus), api.inventory(soldOut),
      ])
      setSummary(nextSummary)
      setReservations(nextReservations.content)
      setInventory(nextInventory.content)
    } catch (requestError) {
      setError(describeError(requestError))
    } finally {
      setLoading(false)
    }
  }, [member?.role, reservationStatus, soldOut])

  useEffect(() => { void load() }, [load])

  if (!member) return <AccessState title="운영 데이터는 로그인이 필요합니다" action="로그인" onAction={onLogin} />
  if (member.role !== 'ADMIN') return <AccessState title="ADMIN 권한이 필요한 화면입니다" action="이벤트로 돌아가기" onAction={() => window.scrollTo({ top: 0 })} />

  return (
    <section className="admin-page">
      <div className="admin-heading">
        <div><p className="eyebrow">OPERATIONS</p><h1>예약 운영 콘솔</h1><p className="muted">예약 상태와 한정 재고를 같은 기준으로 확인합니다.</p></div>
        <button className="button dark" disabled={loading} onClick={() => void load()}>새로고침</button>
      </div>
      {error && <ErrorPanel message={error} retry={load} />}
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
    </section>
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

function AccessState({ title, action, onAction }: { title: string; action: string; onAction: () => void }) {
  return <section className="access-state"><span className="lock-icon">⌁</span><p className="eyebrow">RESTRICTED AREA</p><h1>{title}</h1><p>회원 권한에 따라 운영 API 접근이 분리되어 있습니다.</p><button className="button primary" onClick={onAction}>{action}</button></section>
}

function CardSkeletons() {
  return <div className="event-grid" aria-label="불러오는 중">{[1, 2, 3, 4].map((item) => <div className="event-card skeleton" key={item}><div /><span /><span /></div>)}</div>
}

function InlineLoading() {
  return <div className="inline-loading"><span />데이터를 불러오는 중입니다.</div>
}

export default App
