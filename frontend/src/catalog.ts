import type { EventSummary } from './types'

export const genres = [
  { value: '', label: '전체' },
  { value: 'CONCERT', label: '콘서트' },
  { value: 'FAN_MEETING', label: '팬미팅' },
  { value: 'PUBLIC_BROADCAST', label: '공개방송' },
  { value: 'MERCHANDISE', label: 'MD' },
]

export const genreLabel = (type: string) => genres.find((genre) => genre.value === type)?.label ?? type

const artwork: Record<string, { image: string; headline: string; copy: string }> = {
  '미드나잇 재즈: Midnight Jazz': { image: 'midnight-jazz', headline: '하루의 끝,\n재즈가 시작되는 시간', copy: '미드나잇 콰르텟이 들려주는 네 악기의 대화.' },
  '포레스트 사운드: Forest Sound': { image: 'forest-sound', headline: '숲의 리듬으로\n채우는 하루', copy: '기타와 보컬, 그리고 가까운 무대. 포레스트 사운드 클럽의 라이브.' },
  '첫 번째 편지: First Letter': { image: 'first-letter', headline: '우리의 이야기를\n처음 꺼내는 날', copy: '편지와 토크, 어쿠스틱 무대로 만나는 서하 프로젝트.' },
  '스튜디오 라이브: Studio Live': { image: 'studio-live', headline: '음악이 만들어지는\n바로 그 순간', copy: '온에어 컬렉티브의 녹음 현장을 가까이에서 만나보세요.' },
  '미드나잇 재즈: 앙코르 세션': { image: 'midnight-jazz', headline: '다시 만나는 재즈', copy: '부산에서 이어지는 미드나잇 재즈의 앙코르.' },
  '첫 번째 편지: 두 번째 이야기': { image: 'first-letter', headline: '다음 장의 이야기', copy: '부산에서 함께 쓰는 두 번째 편지.' },
}

// Demo artwork is mapped by the exact fixture title, never by a database ID.
export function eventArtwork(event: Pick<EventSummary, 'title' | 'artistName'>) {
  const expectedArtist: Record<string, string> = {
    'midnight-jazz': '미드나잇 콰르텟', 'forest-sound': '포레스트 사운드 클럽',
    'first-letter': '서하 프로젝트', 'studio-live': '온에어 컬렉티브',
  }
  const value = artwork[event.title]
  return value && expectedArtist[value.image] === event.artistName
    ? { ...value, src: `/images/events/${value.image}.jpg` } : null
}

export function saleState(event: Pick<EventSummary, 'status' | 'salesStartAt' | 'salesEndAt'>, now = Date.now()) {
  if (now >= Date.parse(event.salesEndAt)) return { label: '판매 마감', available: false }
  if (now < Date.parse(event.salesStartAt)) return { label: '오픈 예정', available: false }
  return event.status === 'ON_SALE'
    ? { label: '예매 중', available: true } : { label: '판매 준비 중', available: false }
}

const date = new Intl.DateTimeFormat('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit', timeZone: 'Asia/Seoul' })
export function dateRange(start: string, end: string) {
  const first = date.format(new Date(start))
  const last = date.format(new Date(end))
  return first === last ? first : `${first} – ${last}`
}
