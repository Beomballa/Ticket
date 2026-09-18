import { useEffect, useRef, useState } from 'react'
import { api, describeError } from './api'
import { dateRange, eventArtwork, genreLabel, genres, saleState } from './catalog'
import { formatCurrency, formatDateTime } from './format'
import type { EventDetail, EventSummary, Page } from './types'

export function CatalogBrowser({ onSelect }: { onSelect: (event: EventDetail) => void }) {
  const [result, setResult] = useState<Page<EventSummary> | null>(null)
  const [draft, setDraft] = useState('')
  const [query, setQuery] = useState({ keyword: '', type: '', page: 0 })
  const [reload, setReload] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [detailError, setDetailError] = useState('')
  const [opening, setOpening] = useState<number | null>(null)
  const [featureIndex, setFeatureIndex] = useState(0)
  const detailRequest = useRef(0)
  const heading = useRef<HTMLHeadingElement>(null)

  useEffect(() => {
    let active = true
    setLoading(true)
    setError('')
    api.listEvents(query.keyword, query.type, query.page).then((page) => {
      if (active) { setResult(page); setFeatureIndex(0) }
    }).catch((failure) => {
      if (active) setError(describeError(failure))
    }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [query, reload])

  useEffect(() => () => { detailRequest.current++ }, [])

  const open = async (id: number) => {
    const requestId = ++detailRequest.current
    setOpening(id)
    setDetailError('')
    try {
      const detail = await api.eventDetail(id)
      if (requestId === detailRequest.current) onSelect(detail)
    } catch (failure) {
      if (requestId === detailRequest.current) setDetailError(describeError(failure))
    } finally {
      if (requestId === detailRequest.current) setOpening(null)
    }
  }

  const events = result?.content ?? []
  const featured = events.filter((event) => eventArtwork(event) && saleState(event).available).slice(0, 4)
  const feature = featured[featureIndex] ?? featured[0]
  const art = feature ? eventArtwork(feature) : null
  const showFeature = !loading && !error && !query.keyword && !query.type && query.page === 0 && feature && art
  const movePage = (page: number) => {
    setQuery({ ...query, page })
    heading.current?.focus()
    heading.current?.scrollIntoView({ block: 'start' })
  }

  return <div className="catalog-page">
    <div className="catalog-toolbar">
      <div><p className="eyebrow">STAGEPASS TICKET</p><p className="catalog-intro">좋아하는 무대와 더 가까이.</p></div>
      <form className="search catalog-search" role="search" onSubmit={(event) => {
        event.preventDefault(); setQuery({ ...query, keyword: draft.trim(), page: 0 })
      }}>
        <label className="sr-only" htmlFor="event-search">공연명 또는 아티스트 검색</label>
        <input id="event-search" type="search" value={draft} onChange={(event) => setDraft(event.target.value)} placeholder="어떤 공연을 찾고 있나요?" />
        <button className="button primary" type="submit">검색</button>
      </form>
    </div>
    <div className="genre-tabs" role="group" aria-label="공연 장르">
      {genres.map((genre) => <button key={genre.value} aria-pressed={query.type === genre.value}
        onClick={() => setQuery({ ...query, type: genre.value, page: 0 })}>{genre.label}</button>)}
    </div>
    <p className="demo-notice"><span>DEMO</span> 가상 공연으로 체험하는 티켓 예매 서비스입니다. 실제 결제는 진행되지 않습니다.</p>

    {showFeature ? <section className={`featured-show theme-${art.image}`} aria-label="주목할 공연">
      <div className="feature-copy">
        <p className="feature-kicker">{genreLabel(feature.type)} · {saleState(feature).label}</p>
        <h1>{art.headline}</h1>
        <p className="feature-description">{art.copy}</p>
        <div className="feature-facts"><strong>{feature.title}</strong>
          {feature.overview && <><span>{feature.overview.venue}</span><span>{dateRange(feature.overview.startsAt, feature.overview.endsAt)}</span></>}
        </div>
        <button className="button primary" aria-busy={opening === feature.id} onClick={() => void open(feature.id)}>공연 상세 · 예매 <span aria-hidden="true">↗</span></button>
        <div className="feature-switcher" role="group" aria-label="주목할 공연 선택">
          {featured.map((event, index) => <button key={event.id} aria-label={event.title} aria-pressed={index === featureIndex}
            onClick={() => setFeatureIndex(index)}>{String(index + 1).padStart(2, '0')}</button>)}
          <span>직접 넘겨보기</span>
        </div>
      </div>
      <img className="feature-poster" src={art.src} alt={`${feature.title} 데모 포스터`} width="640" height="960" fetchPriority="high" />
    </section> : <div className="catalog-title"><h1>{query.keyword ? `“${query.keyword}” 검색 결과` : '어떤 무대를 만나볼까요?'}</h1><p>공연과 아티스트를 찾고, 원하는 회차를 선택하세요.</p></div>}

    <section className="catalog-results" aria-labelledby="catalog-heading" aria-busy={loading}>
      <div className="section-heading"><div><h2 id="catalog-heading" ref={heading} tabIndex={-1}>{query.keyword ? '검색한 공연' : `${genreLabel(query.type)} 공연`}</h2>
        <p className="catalog-note" role="status">{loading ? '공연을 불러오는 중입니다.' : `총 ${result?.totalElements ?? 0}개 · 판매 시작일 최신순`}</p></div>
        {(query.keyword || query.type) && <button className="button ghost" onClick={() => { setDraft(''); setQuery({ keyword: '', type: '', page: 0 }) }}>전체 공연 보기</button>}
      </div>
      {detailError && <p className="error-panel" role="alert">{detailError} · 공연을 다시 선택해 주세요.</p>}
      {opening !== null && <p role="status" className="catalog-note">공연 상세를 불러오고 있습니다.</p>}
      {error ? <div className="catalog-error" role="alert"><h3>공연 목록을 불러오지 못했습니다.</h3><p>{error}</p><p>로컬 시연 중이라면 백엔드 서버가 실행 중인지 확인해 주세요.</p><button className="button ghost" onClick={() => setReload(reload + 1)}>다시 시도</button></div>
        : loading ? <div className="poster-grid" aria-hidden="true">{[0, 1, 2, 3].map((id) => <div className="poster-skeleton" key={id} />)}</div>
        : events.length === 0 ? <div className="empty-state"><strong>조건에 맞는 공연이 없습니다.</strong><p>다른 검색어나 장르를 선택해 보세요.</p></div>
        : <div className="poster-grid">{events.map((event) => {
          const poster = eventArtwork(event)
          const sale = saleState(event)
          return <button className="show-card" key={event.id} onClick={() => void open(event.id)} aria-busy={opening === event.id}>
            <div className="poster-frame">{poster ? <img src={poster.src} alt="" width="640" height="960" loading="lazy" />
              : <div className="poster-unavailable"><span>{genreLabel(event.type)}</span><strong>{event.title}</strong><small>포스터 준비 중</small></div>}
              {poster && <span className="demo-tag">DEMO</span>}
            </div>
            <div className="show-card-meta"><span className={sale.available ? 'sale-label' : 'muted'}>{sale.label}</span><span>{genreLabel(event.type)}</span></div>
            <h3>{event.title}</h3><p className="show-artist">{event.artistName}</p>
            <p className="show-venue">{event.overview?.venue ?? '공연장 상세 확인'}</p>
            <p className="show-date">{event.overview ? dateRange(event.overview.startsAt, event.overview.endsAt) : '공연 일정 상세 확인'}</p>
            <p className="show-price">{event.overview?.minPrice != null ? `${formatCurrency(event.overview.minPrice)}부터` : '가격 상세 확인'}</p>
            {!sale.available && <p className="show-open-time">판매 시작 {formatDateTime(event.salesStartAt)}</p>}
          </button>
        })}</div>}
      {!loading && !error && result && result.totalPages > 1 && <div className="catalog-pagination" aria-label="공연 목록 페이지">
        <button className="button ghost" disabled={query.page === 0} onClick={() => movePage(query.page - 1)}>이전</button>
        <span>{query.page + 1} / {result.totalPages}</span>
        <button className="button ghost" disabled={query.page + 1 >= result.totalPages} onClick={() => movePage(query.page + 1)}>다음</button>
      </div>}
    </section>
    <section className="booking-guide" aria-label="예매 안내"><h2>처음 예매하시나요?</h2><p>공연과 회차 선택 → 로그인 후 1매 선점 → 10분 안에 모의 결제 → 내 예약에서 확인</p><span>모든 입장권은 비지정석입니다. 잔여 수량과 판매 가능 여부는 예매 시 다시 확인합니다.</span></section>
  </div>
}
