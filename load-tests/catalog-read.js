import http from 'k6/http'
import { check, sleep } from 'k6'

const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8080'

export const options = {
  scenarios: {
    cached_event_detail: {
      executor: 'ramping-vus',
      stages: [
        { duration: '2s', target: 10 },
        { duration: '5s', target: 30 },
        { duration: '2s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<200'],
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
}

export function setup() {
  const catalog = http.get(`${baseUrl}/api/events?status=ON_SALE&keyword=Load%20Test&size=10`)
  check(catalog, { 'load event list is available': (response) => response.status === 200 })
  const eventId = catalog.json('content.0.id')
  if (!eventId) throw new Error('Load Test 이벤트를 찾을 수 없습니다. load-test-data.sql을 먼저 실행하세요.')
  return { eventId }
}

export default function (data) {
  const response = http.get(`${baseUrl}/api/events/${data.eventId}`)
  check(response, {
    'cached detail succeeds': (result) => result.status === 200,
    'detail contains inventory': (result) => Number(result.json('sessions.0.inventory.0.id')) > 0,
  })
  sleep(0.05)
}
