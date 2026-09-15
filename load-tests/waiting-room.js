import http from 'k6/http'
import { check, sleep } from 'k6'
import { Counter, Rate } from 'k6/metrics'

const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8080'
const users = Number(__ENV.LOAD_USERS || 20)
const admitted = new Counter('waiting_room_admitted')
const unexpected = new Rate('waiting_room_unexpected')

export const options = {
  scenarios: { fair_join_and_poll: { executor: 'per-vu-iterations', vus: users, iterations: 1, maxDuration: '2m' } },
  thresholds: {
    http_req_duration: ['p(95)<500'],
    checks: ['rate>0.99'],
    waiting_room_unexpected: ['rate<0.01'],
  },
}

const jsonHeaders = { 'Content-Type': 'application/json' }

export function setup() {
  let eventId = Number(__ENV.EVENT_ID)
  if (!eventId) {
    const catalog = http.get(`${baseUrl}/api/events?status=ON_SALE&keyword=Load%20Test&size=10`)
    check(catalog, { 'load event is available': (response) => response.status === 200 })
    eventId = Number(catalog.json('content.0.id'))
  }
  if (!eventId) throw new Error('Load Test 이벤트를 찾을 수 없습니다.')

  const adminLogin = http.post(`${baseUrl}/api/auth/login`, JSON.stringify({
    email: 'load-admin@stagepass.local', password: 'LoadPass123!',
  }), { headers: jsonHeaders })
  check(adminLogin, { 'admin login succeeds': (response) => response.status === 200 })
  const adminToken = adminLogin.json('accessToken')
  const configured = http.put(
    `${baseUrl}/api/admin/events/${eventId}/waiting-room`,
    JSON.stringify({
      enabled: true,
      batchSize: Math.min(5, users),
      activeCapacity: users,
      admissionTtl: 'PT2M',
    }),
    { headers: { ...jsonHeaders, Authorization: `Bearer ${adminToken}` } },
  )
  check(configured, { 'waiting room policy is configured': (response) => response.status === 204 })

  const tokens = []
  for (let number = 1; number <= users; number += 1) {
    const login = http.post(`${baseUrl}/api/auth/login`, JSON.stringify({
      email: `load-user-${number}@stagepass.local`, password: 'LoadPass123!',
    }), { headers: jsonHeaders })
    check(login, { 'login succeeds': (response) => response.status === 200 })
    tokens.push(login.json('accessToken'))
  }
  return { eventId, tokens, adminToken }
}

export default function (data) {
  const headers = { ...jsonHeaders, Authorization: `Bearer ${data.tokens[__VU - 1]}` }
  const joined = http.post(`${baseUrl}/api/events/${data.eventId}/waiting-room`, null, { headers })
  check(joined, {
    'join succeeds': (response) => response.status === 200,
    'position is assigned': (response) => response.json('status') !== 'WAITING'
      || Number(response.json('position')) > 0,
  })
  let status = joined
  for (let poll = 0; poll < 60 && status.json('status') === 'WAITING'; poll += 1) {
    sleep(1)
    status = http.get(`${baseUrl}/api/events/${data.eventId}/waiting-room`, { headers })
  }
  const entered = status.status === 200 && status.json('status') === 'ADMITTED'
  admitted.add(entered ? 1 : 0)
  unexpected.add(!entered)
  check(status, { 'member is eventually admitted': () => entered })
}

export function teardown(data) {
  const closed = http.del(`${baseUrl}/api/admin/events/${data.eventId}/waiting-room`, null, {
    headers: { Authorization: `Bearer ${data.adminToken}` },
  })
  check(closed, { 'waiting room policy is closed': (response) => response.status === 204 })
}
