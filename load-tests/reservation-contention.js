import http from 'k6/http'
import { check, sleep } from 'k6'
import { Counter, Rate } from 'k6/metrics'

const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8080'
const users = Number(__ENV.LOAD_USERS || 20)
const iterations = Number(__ENV.LOAD_ITERATIONS || 200)

const held = new Counter('reservation_held')
const soldOut = new Counter('reservation_sold_out')
const rateLimited = new Counter('reservation_rate_limited')
const unexpected = new Rate('reservation_unexpected_response')

export const options = {
  scenarios: {
    inventory_contention: {
      executor: 'shared-iterations',
      vus: users,
      iterations,
      maxDuration: '2m',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<500'],
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
    reservation_unexpected_response: ['rate<0.01'],
    reservation_rate_limited: ['count==0'],
  },
}

const jsonHeaders = { 'Content-Type': 'application/json' }

export function setup() {
  const catalog = http.get(`${baseUrl}/api/events?status=ON_SALE&keyword=Load%20Test&size=10`)
  check(catalog, { 'load event list is available': (response) => response.status === 200 })
  const eventId = catalog.json('content.0.id')
  if (!eventId) throw new Error('Load Test 이벤트를 찾을 수 없습니다. load-test-data.sql을 먼저 실행하세요.')

  const detail = http.get(`${baseUrl}/api/events/${eventId}`)
  check(detail, { 'load event detail is available': (response) => response.status === 200 })
  const inventoryId = detail.json('sessions.0.inventory.0.id')
  if (!inventoryId) throw new Error('Load Test 재고를 찾을 수 없습니다.')

  const tokens = []
  for (let number = 1; number <= users; number += 1) {
    const login = http.post(
      `${baseUrl}/api/auth/login`,
      JSON.stringify({ email: `load-user-${number}@stagepass.local`, password: 'LoadPass123!' }),
      { headers: jsonHeaders },
    )
    check(login, { 'load user login succeeds': (response) => response.status === 200 })
    tokens.push(login.json('accessToken'))
  }
  return { inventoryId, tokens }
}

export default function (data) {
  const token = data.tokens[(__VU - 1) % data.tokens.length]
  const requestId = `k6-${__VU}-${__ITER}-${Date.now()}`
  const headers = {
    ...jsonHeaders,
    Authorization: `Bearer ${token}`,
    'Idempotency-Key': `hold-${requestId}`,
    'X-Request-Id': requestId,
  }
  const hold = http.post(
    `${baseUrl}/api/reservations`,
    JSON.stringify({ items: [{ inventoryId: data.inventoryId, quantity: 1 }] }),
    { headers, responseCallback: http.expectedStatuses(201, 409, 429) },
  )

  if (hold.status === 201) {
    held.add(1)
    const confirmation = http.post(
      `${baseUrl}/api/reservations/${hold.json('id')}/confirm`,
      JSON.stringify({ paymentToken: 'mock-approved' }),
      {
        headers: { ...headers, 'Idempotency-Key': `confirm-${requestId}` },
        responseCallback: http.expectedStatuses(200),
      },
    )
    unexpected.add(confirmation.status !== 200)
    check(confirmation, { 'held reservation confirms': (response) => response.status === 200 })
  } else if (hold.status === 409 && hold.json('code') === 'INSUFFICIENT_STOCK') {
    soldOut.add(1)
  } else if (hold.status === 429) {
    rateLimited.add(1)
  } else {
    unexpected.add(true)
  }

  check(hold, {
    'hold is accepted or inventory is sold out': (response) =>
      response.status === 201 || (response.status === 409 && response.json('code') === 'INSUFFICIENT_STOCK'),
  })
  sleep(0.02)
}
