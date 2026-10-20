// 정합성 테스트(NFR-034): 송금 확정에 동시 요청을 건다. loadtest/integrity.sh가 시나리오마다 한 번씩 실행한다.
// 환경변수: BASE_URL, SCENARIO(dup | overdraw), ROUNDS(보내는 회원 수 = 반복 수, 기본 100), PARALLEL(동시 확정 수, 기본 10),
// BALANCE(동시 출금 계좌의 시작 잔액, 기본 100000 — integrity.sh가 DB에 맞춰 둔다).
// - dup(같은 요청ID 연타): 요청 하나를 만들고 같은 요청ID로 PARALLEL개를 동시에 확정한다. 성공은 1개여야 한다.
// - overdraw(같은 계좌 동시 출금): 같은 계좌에서 서로 다른 PARALLEL명에게 보내는 요청 PARALLEL개를 동시에 확정한다.
//   성공은 BALANCE ÷ 금액(내림)개여야 한다. 받는 쪽을 달리해야 잔액이 틀어졌을 때 전체 합계로 드러난다.
// 판정은 k6가 아니라 끝난 뒤의 SQL 3개다(integrity.sh). 회원 번호: dup 보내는 쪽 1…R, 동시 출금 보내는 쪽 R+1…2R,
// dup 받는 쪽 2R+1…3R, 동시 출금 받는 쪽 3R+1…(3+P)R. 시드의 SEED_USERS가 그만큼 있어야 한다.
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const SCENARIO = __ENV.SCENARIO || 'dup';
const ROUNDS = Number(__ENV.ROUNDS || 100);
const PARALLEL = Number(__ENV.PARALLEL || 10);
const BALANCE = Number(__ENV.BALANCE || 100000);
const PASSWORD = 'pw12345!';
const ACCOUNT_PASSWORD = '1234';
const DUP_AMOUNT = 1000;
const OVERDRAW_AMOUNT = 30000;

const confirmOk = new Counter('confirm_ok');
const confirmRejected = new Counter('confirm_rejected'); // 4xx: 이미 확정, 잔액 부족
const confirmError = new Counter('confirm_error'); // 5xx: 낙관적 락 충돌 등
const confirmExpected = new Counter('confirm_expected'); // 정합성이 지켜졌다면 성공해야 하는 수

export const options = {
  scenarios: { [SCENARIO]: { executor: 'shared-iterations', vus: 20, iterations: ROUNDS, maxDuration: '10m' } },
  batch: 20,
  batchPerHost: 20, // 기본 6이면 동시 확정이 6개씩 나가 버린다
  thresholds: {
    // k6는 기준이 걸린 하위 지표만 요약에 낸다. 판정 기준은 아니다.
    'http_reqs{name:confirm}': ['count>=0'],
    'http_req_duration{name:confirm}': ['p(95)<1000000'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'max'],
};

const accountNumber = (userNo) => String(100000000000 + userNo);
const senderNo = (i) => (SCENARIO === 'dup' ? 0 : ROUNDS) + i + 1;
const json = { 'Content-Type': 'application/json' };

// 보내는 회원마다 한 번만 로그인한다(BCrypt를 재지 않게). 토큰은 1시간 유효하다.
export function setup() {
  const tokens = [];
  for (let from = 0; from < ROUNDS; from += 20) {
    const calls = [];
    for (let i = from; i < Math.min(from + 20, ROUNDS); i++) {
      const loginId = 'load' + String(senderNo(i)).padStart(6, '0');
      calls.push(['POST', `${BASE}/api/v1/auth/login`, JSON.stringify({ loginId, password: PASSWORD }), { headers: json }]);
    }
    for (const res of http.batch(calls)) tokens.push(res.json('data.accessToken'));
  }
  return tokens;
}

function createRequest(token, recipientNo, amount) {
  const res = http.post(
    `${BASE}/internal/transfer-requests`,
    JSON.stringify({ recipientAccountNumber: accountNumber(recipientNo), amount }),
    { headers: { ...json, Authorization: `Bearer ${token}` }, tags: { name: 'create' } },
  );
  check(res, { '송금 요청 생성': (r) => r.status < 300 });
  return res.json('data.requestId');
}

export default function (tokens) {
  const i = exec.scenario.iterationInTest;
  const token = tokens[i];
  const ids = [];
  if (SCENARIO === 'dup') {
    const id = createRequest(token, 2 * ROUNDS + i + 1, DUP_AMOUNT);
    for (let k = 0; k < PARALLEL; k++) ids.push(id);
    confirmExpected.add(1);
  } else {
    for (let k = 0; k < PARALLEL; k++) ids.push(createRequest(token, 3 * ROUNDS + i * PARALLEL + k + 1, OVERDRAW_AMOUNT));
    confirmExpected.add(Math.floor(BALANCE / OVERDRAW_AMOUNT));
  }
  const params = { headers: { ...json, Authorization: `Bearer ${token}` }, tags: { name: 'confirm' } };
  const body = JSON.stringify({ password: ACCOUNT_PASSWORD });
  const responses = http.batch(ids.map((id) => ['POST', `${BASE}/api/v1/transfer-requests/${id}/confirm`, body, params]));
  for (const res of responses) {
    if (res.status === 200) confirmOk.add(1);
    else if (res.status < 500) confirmRejected.add(1);
    else confirmError.add(1);
  }
}
