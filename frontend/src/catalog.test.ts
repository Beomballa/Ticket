import { describe, expect, it } from 'vitest'
import { dateRange, eventArtwork, saleState } from './catalog'

describe('catalog presentation', () => {
  const period = { salesStartAt: '2026-10-01T00:00:00Z', salesEndAt: '2026-10-10T00:00:00Z' }
  it('does not offer booking before opening, after closing, or while merely published', () => {
    expect(saleState({ ...period, status: 'ON_SALE' }, Date.parse(period.salesStartAt) - 1).available).toBe(false)
    expect(saleState({ ...period, status: 'ON_SALE' }, Date.parse(period.salesStartAt)).available).toBe(true)
    expect(saleState({ ...period, status: 'ON_SALE' }, Date.parse(period.salesEndAt)).available).toBe(false)
    expect(saleState({ ...period, status: 'PUBLISHED' }, Date.parse(period.salesStartAt)).available).toBe(false)
  })
  it('maps only matching fictional artists and titles to demo artwork', () => {
    expect(eventArtwork({ title: '포레스트 사운드: Forest Sound', artistName: '포레스트 사운드 클럽' })?.src).toBe('/images/events/forest-sound.jpg')
    expect(eventArtwork({ title: '포레스트 사운드: Forest Sound', artistName: '다른 아티스트' })).toBeNull()
    expect(eventArtwork({ title: '사용자가 등록한 공연', artistName: '새 아티스트' })).toBeNull()
  })
  it('uses the Korean performance date and collapses a one-day range', () => {
    expect(dateRange('2026-10-01T23:00:00Z', '2026-10-02T01:00:00Z')).toBe('2026. 10. 02.')
  })
})
