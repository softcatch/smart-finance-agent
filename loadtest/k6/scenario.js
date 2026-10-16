// 전체 시나리오 부하 테스트(NFR-031): 행동 유형 4개를 DART 50·송금 30·내역 15·가입 5% 비율로 건다.
// loadtest/run.sh가 실행한다. 환경변수: BASE_URL, TARGET_TPS(요청/초, 기본 93), DURATION(유지 시간, 기본 3m),
// WARMUP(올리는 시간, 기본 30s), USERS(시드 회원 수), RUN_ID(가입 아이디가 겹치지 않게).
// 세션은 열린 모델(constant 도착률)로 도착한다 — 서버가 느려져도 도착률이 줄지 않는다. 생각하는 시간은
// 압축해서(0.3초) 세션이 오래 머물지 않게 한다. 요청 수는 개요 6절의 행동 유형 표와 같다.
import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const TARGET_TPS = Number(__ENV.TARGET_TPS || 93);
const DURATION = __ENV.DURATION || '3m';
const WARMUP = __ENV.WARMUP || '30s';
const USERS = Number(__ENV.USERS || 10000);
const RUN_ID = __ENV.RUN_ID || String(Date.now());
const PASSWORD = 'pw12345!'; // 시드 회원 공통
const ACCOUNT_PASSWORD = '1234';
const WARMUP_MS = Number(WARMUP.replace('s', '')) * 1000;

// 유형별 비율과 세션당 요청 수(개요 6절): 가중평균 5.65개
const MIX = { dart: 0.5, transfer: 0.3, history: 0.15, signup: 0.05 };
const REQUESTS = { dart: 6, transfer: 6, history: 4, signup: 5 };
const perSession = Object.keys(MIX).reduce((sum, type) => sum + MIX[type] * REQUESTS[type], 0);
const sessionsPerSec = TARGET_TPS / perSession;

function arrival(type, vus) {
  const rate = Math.max(1, Math.round(sessionsPerSec * MIX[type] * 10)); // 10초당 세션 수(0.1/s 단위)
  return {
    executor: 'ramping-arrival-rate',
    exec: type,
    startRate: 0,
    timeUnit: '10s',
    preAllocatedVUs: vus,
    maxVUs: vus * 4,
    stages: [
      { target: rate, duration: WARMUP },
      { target: rate, duration: DURATION },
    ],
  };
}

// 합격 기준 중 "채팅 p95 < 가짜 지연 p95 + 1초": 가짜 LLM이 재생하는 분포(latency.json)에서 턴마다 호출 지연의
// 합의 p95를 몬테카를로(시드 고정)로 구한다.
const LATENCY = JSON.parse(open('../../ai/loadtest/latency.json')).samples_ms;
function sumP95(kinds) {
  let seed = 1;
  const random = () => {
    seed = (seed * 1664525 + 1013904223) % 4294967296;
    return seed / 4294967296;
  };
  const sums = [];
  for (let i = 0; i < 20000; i++) {
    let total = 0;
    for (const kind of kinds) total += LATENCY[kind][Math.floor(random() * LATENCY[kind].length)];
    sums.push(total);
  }
  sums.sort((a, b) => a - b);
  return sums[Math.ceil(0.95 * sums.length) - 1];
}
const LIMIT = {
  chat_financial: sumP95(['extract']) + 1000, // 재무 비교·경쟁사 비교: 의도 추출만 LLM
  chat_business: sumP95(['extract', 'embedding', 'business']) + 1000, // 사업의 내용: 추출 + 임베딩 + 답변
  chat_transfer: sumP95(['extract']) + 1000, // 송금 요청: 의도 추출만 LLM
  detail: sumP95(['report']) + 1000, // 상세 보기: 보고서 생성
};

export const options = {
  scenarios: {
    dart: arrival('dart', 250),
    transfer: arrival('transfer', 80),
    history: arrival('history', 10),
    signup: arrival('signup', 10),
  },
  thresholds: {
    // 올리는 구간(phase:warmup)은 판정에서 뺀다
    'http_req_duration{kind:api,phase:hold}': ['p(95)<500'],
    'http_req_duration{name:chat_financial,phase:hold}': [`p(95)<${LIMIT.chat_financial}`],
    'http_req_duration{name:chat_business,phase:hold}': [`p(95)<${LIMIT.chat_business}`],
    'http_req_duration{name:chat_transfer,phase:hold}': [`p(95)<${LIMIT.chat_transfer}`],
    'http_req_duration{name:detail,phase:hold}': [`p(95)<${LIMIT.detail}`],
    'http_req_failed{phase:hold}': ['rate<0.01'],
    checks: ['rate>0.99'],
    dropped_iterations: ['count==0'], // 목표 도착률을 못 채우면(VU 한도) 한계에 걸린 것이다
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'max'],
};

const PAIRS = [
  ['삼성전자', 'SK하이닉스'],
  ['현대자동차', '기아'],
  ['LG에너지솔루션', '삼성SDI'],
  ['NAVER', '카카오'],
];
const pick = (list) => list[Math.floor(Math.random() * list.length)];
const accountNumber = (userNo) => String(100000000000 + userNo);
const randomUser = () => 1 + Math.floor(Math.random() * USERS);

// 요청 하나. name은 경로 대신 쓰는 라벨(경로에 ID가 들어가 태그가 늘어나지 않게), kind는 합격 기준 묶음이다.
function call(method, path, body, ctx, name, kind, headers = {}) {
  const phase = exec.instance.currentTestRunDuration < WARMUP_MS ? 'warmup' : 'hold';
  const params = {
    headers: { 'Content-Type': 'application/json', ...headers },
    tags: { name, kind, phase },
  };
  if (ctx.token) params.headers.Authorization = `Bearer ${ctx.token}`;
  return http.request(method, BASE + path, body === null ? null : JSON.stringify(body), params);
}

// 응답이 기대대로면 값을, 아니면 null을 돌려준다. 실패하면 세션을 끝낸다(뒤 요청이 의미 없다).
function ok(res, label, status = 200) {
  const isOk = (r) => {
    try {
      return r.status === status && r.json('success') === true;
    } catch (e) {
      return false; // JSON이 아닌 응답(게이트웨이 오류 등)
    }
  };
  return check(res, { [label]: isOk }) ? res : null;
}

function login(ctx, loginId) {
  const res = ok(call('POST', '/api/v1/auth/login', { loginId, password: PASSWORD }, ctx, 'login', 'api'), 'login');
  if (!res) return false;
  ctx.token = res.json('data.accessToken');
  return true;
}

function newThread(ctx) {
  const res = ok(call('POST', '/api/v1/chat/threads', null, ctx, 'new_thread', 'api'), 'new thread', 201);
  return res && res.json('data.threadId');
}

function chat(ctx, threadId, message, name) {
  const kind = 'chat';
  const body = { message };
  return ok(call('POST', `/api/v1/chat/threads/${threadId}/messages`, body, ctx, name, kind), name);
}

function think() {
  sleep(0.3);
}

// DART 분석형: 로그인 → 새 대화 → 질문 3회 → 상세 보기(6)
export function dart() {
  const ctx = {};
  const [a, b] = pick(PAIRS);
  if (!login(ctx, 'load' + String(randomUser()).padStart(6, '0'))) return;
  think();
  const threadId = newThread(ctx);
  if (!threadId) return;
  think();
  if (!chat(ctx, threadId, `${a}랑 ${b} 실적 비교해줘`, 'chat_financial')) return;
  think();
  if (!chat(ctx, threadId, `${a} 사업 내용 알려줘`, 'chat_business')) return;
  think();
  const last = chat(ctx, threadId, `${b} 경쟁사 비교해줘`, 'chat_financial');
  const detailId = last && last.json('data.detailId');
  check(last, { 'detailId 발급': () => !!detailId });
  if (!detailId) return;
  think();
  ok(call('GET', `/api/v1/chat/details/${detailId}`, null, ctx, 'detail', 'detail'), 'detail');
}

// 송금형: 로그인 → 계좌 조회 → 새 대화 → 송금 메시지 → [보내기] → 내역(6)
export function transfer() {
  const ctx = {};
  const me = randomUser();
  let other = randomUser();
  if (other === me) other = (me % USERS) + 1;
  if (!login(ctx, 'load' + String(me).padStart(6, '0'))) return;
  think();
  if (!ok(call('GET', '/api/v1/accounts', null, ctx, 'accounts', 'api'), 'accounts')) return;
  think();
  const threadId = newThread(ctx);
  if (!threadId) return;
  think();
  const amount = 1000 * (1 + Math.floor(Math.random() * 9));
  const card = chat(ctx, threadId, `${accountNumber(other)}로 ${amount}원 보내줘`, 'chat_transfer');
  const requestId = card && card.json('data.transferCard.requestId');
  check(card, { '확인 카드': () => !!requestId });
  if (!requestId) return;
  think();
  const confirm = call('POST', `/api/v1/transfer-requests/${requestId}/confirm`, { password: ACCOUNT_PASSWORD }, ctx, 'confirm', 'api');
  if (!ok(confirm, 'confirm')) return;
  think();
  ok(call('GET', '/api/v1/transfers?size=20', null, ctx, 'transfers', 'api'), 'transfers');
}

// 내역 확인형: 로그인 → 계좌 조회 → 내역 스크롤 2회(4)
export function history() {
  const ctx = {};
  if (!login(ctx, 'load' + String(randomUser()).padStart(6, '0'))) return;
  think();
  if (!ok(call('GET', '/api/v1/accounts', null, ctx, 'accounts', 'api'), 'accounts')) return;
  think();
  const first = ok(call('GET', '/api/v1/transfers?size=20', null, ctx, 'transfers', 'api'), 'transfers');
  if (!first) return;
  const cursor = first.json('data.nextCursor');
  think();
  ok(call('GET', `/api/v1/transfers?size=20&cursor=${cursor}`, null, ctx, 'transfers', 'api'), 'transfers (cursor)');
}

// 신규 가입형: 가입 → 로그인 → 계좌 개설 → 충전 → 별칭 등록(5)
export function signup() {
  const ctx = {};
  const n = exec.scenario.iterationInTest;
  const loginId = `new${RUN_ID}_${n}`;
  const signupRes = call('POST', '/api/v1/auth/signup', { loginId, password: PASSWORD, name: '신규' }, ctx, 'signup', 'api');
  if (!ok(signupRes, 'signup', 201)) return;
  think();
  if (!login(ctx, loginId)) return;
  think();
  const open = call('POST', '/api/v1/accounts', { password: ACCOUNT_PASSWORD }, ctx, 'open_account', 'api', {
    'Idempotency-Key': `${RUN_ID}-${n}`,
  });
  const opened = ok(open, 'open account', 201);
  if (!opened) return;
  think();
  const accountId = opened.json('data.accountId');
  if (!ok(call('POST', `/api/v1/accounts/${accountId}/charge`, { amount: 100000 }, ctx, 'charge', 'api'), 'charge')) return;
  think();
  const alias = { alias: '친구', accountNumber: accountNumber(randomUser()) };
  ok(call('POST', '/api/v1/aliases', alias, ctx, 'alias', 'api'), 'alias', 201);
}
