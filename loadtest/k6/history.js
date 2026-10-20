// 송금 내역 목록 성능 테스트(NFR-030): GET /api/v1/transfers. 내역이 많은 계정(시드의 SEED_HEAVY)에서 페이지 깊이별 지연을 잰다.
// loadtest/history.sh가 실행한다. 환경변수: BASE_URL, MODE(cursor 기본 | offset | all), SIZES(계정의 내역 건수, 쉼표로 —
// 시드의 SEED_HEAVY와 같게), RATE(요청/초, 기본 20), DURATION(건수마다, 기본 30s), CURSORS_FILE(cursor일 때 필요 — 건수별·
// 깊이별 커서 ID가 든 JSON. history.sh가 DB에서 뽑아 만든다).
// offset·all은 loadtest/variants/transfer-history.patch를 적용한 측정용 서버에만 있다(전체 조회 → 오프셋 → 커서 비교).
// 건수마다 시나리오를 차례로 돌린다(서로 겹치지 않게 startTime을 띄운다). 합격 기준: p95 < 500ms.
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const MODE = __ENV.MODE || 'cursor';
const SIZES = (__ENV.SIZES || '1000,10000,100000').split(',').map(Number);
const RATE = Number(__ENV.RATE || 20);
const DURATION = __ENV.DURATION || '30s';
const SECONDS = Number(DURATION.replace('s', ''));
const PAGE = 20;
const PASSWORD = 'pw12345!';

// 첫 페이지와 전체의 10·50·90·99% 지점. 오프셋 방식은 깊을수록 느려지고 커서 방식은 깊이와 상관없어야 한다.
const DEPTHS = { first: 0, p10: 0.1, p50: 0.5, p90: 0.9, p99: 0.99 };
const depthNames = MODE === 'all' ? ['all'] : Object.keys(DEPTHS);

// 깊이별 커서: 그 계정의 내역을 최신순으로 세어 그 지점의 송금 ID. 내역이 전체 송금 사이에 흩어져 있어서 ID로 계산할 수 없다.
const CURSORS = MODE === 'cursor' ? JSON.parse(open(__ENV.CURSORS_FILE)) : {};

if (!/^\d+s$/.test(DURATION)) throw new Error(`DURATION은 30s처럼 초 단위로 적는다: ${DURATION}`);

const scenarios = {};
const thresholds = { http_req_failed: ['rate<0.01'] };
SIZES.forEach((n, index) => {
  scenarios[`s${n}`] = {
    executor: 'constant-arrival-rate',
    exec: 'run',
    rate: RATE,
    timeUnit: '1s',
    duration: DURATION,
    startTime: `${index * (SECONDS + 10)}s`,
    preAllocatedVUs: 20,
    maxVUs: 200,
  };
  for (const depth of depthNames) {
    thresholds[`http_req_duration{size:${n},depth:${depth}}`] = ['p(95)<500'];
  }
});
export const options = { scenarios, thresholds, summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'max'] };

function login(loginId) {
  const res = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ loginId, password: PASSWORD }), {
    headers: { 'Content-Type': 'application/json' },
  });
  return res.json('data.accessToken');
}

// 건수별 회원으로 한 번만 로그인한다(BCrypt를 재지 않게). 토큰은 1시간 유효하다.
export function setup() {
  return SIZES.map((n) => ({ n, token: login(`heavy${n}`) }));
}

export function run(data) {
  const { n, token } = data.find((d) => `s${d.n}` === exec.scenario.name);
  const depth = depthNames[Math.floor(Math.random() * depthNames.length)];
  const rows = Math.floor(n * (DEPTHS[depth] || 0));
  let path = `/api/v1/transfers?size=${PAGE}`;
  if (MODE === 'cursor' && rows > 0) path += `&cursor=${CURSORS[n][depth]}`;
  if (MODE === 'offset') path = `/api/v1/transfers/offset?size=${PAGE}&page=${Math.floor(rows / PAGE)}`;
  if (MODE === 'all') path = '/api/v1/transfers/all';
  const res = http.get(BASE + path, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { name: `history_${MODE}`, size: String(n), depth },
    timeout: '60s',
  });
  check(res, { 'items가 온다': (r) => r.status === 200 && r.json('data.items.length') > 0 });
}
