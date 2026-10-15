<%@ page contentType="text/html;charset=UTF-8" isELIgnored="true" %>
<!DOCTYPE html>
<html lang="ko">
<head><meta charset="UTF-8"><title>계좌</title></head>
<body>
<%@ include file="nav.jspf" %>
<h1>계좌</h1>
<table id="accounts">
  <thead><tr><th>계좌번호</th><th>잔액</th><th>대표</th><th>충전</th></tr></thead>
  <tbody></tbody>
</table>
<h2>계좌 개설</h2>
<form id="open-form">
  <input name="password" type="password" inputmode="numeric" placeholder="계좌 비밀번호(숫자 4자리)" required>
  <button type="submit">개설</button>
</form>
<p id="msg"></p>
<script>
const token = localStorage.getItem('accessToken');
const tbody = document.querySelector('#accounts tbody');
const msg = document.getElementById('msg');

// 개설 요청의 멱등키 — 같은 키로 다시 눌러도 계좌가 중복 생기지 않는다. 성공하면 새로 만든다.
// crypto.randomUUID는 보안 컨텍스트(https·localhost)에서만 있어 쓰지 않는다.
function newKey() {
  return 'k' + Date.now().toString(36) + Math.random().toString(36).slice(2);
}
let openKey = newKey();

// 서버 응답({success, data, error})을 돌려준다. 401이면 로그인 페이지로 보낸다.
async function call(method, path, extraHeaders, body) {
  const headers = Object.assign(
    { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token },
    extraHeaders);
  const res = await fetch(path, { method, headers, body: body && JSON.stringify(body) });
  if (res.status === 401) {
    location.href = '/login';
    throw new Error('unauthorized');
  }
  return res.json();
}

function won(n) {
  return n.toLocaleString('ko-KR') + '원';
}

function row(account) {
  const tr = document.createElement('tr');
  const number = document.createElement('td');
  number.textContent = account.accountNumber;
  const balance = document.createElement('td');
  balance.className = 'balance';
  balance.textContent = won(account.balance);
  const primary = document.createElement('td');
  primary.textContent = account.primary ? '대표' : '';

  const amount = document.createElement('input');
  amount.type = 'number';
  amount.name = 'amount';
  amount.min = '1';
  amount.placeholder = '충전 금액';
  const button = document.createElement('button');
  button.textContent = '충전';
  // 충전 API에는 멱등키가 없어 요청마다 잔액이 늘어난다 — 처리 중에는 버튼을 막아 한 번만 보낸다.
  button.addEventListener('click', async () => {
    button.disabled = true;
    msg.textContent = '';
    try {
      const body = await call('POST', '/api/v1/accounts/' + account.accountId + '/charge', {},
        { amount: Number(amount.value) });
      if (body.success) load();
      else msg.textContent = body.error.message;
    } catch (err) {
      msg.textContent = '서버에 연결할 수 없습니다.';
    } finally {
      button.disabled = false;
    }
  });
  const charge = document.createElement('td');
  charge.append(amount, button);

  tr.append(number, balance, primary, charge);
  return tr;
}

async function load() {
  try {
    const body = await call('GET', '/api/v1/accounts', {});
    if (body.success) tbody.replaceChildren(...body.data.accounts.map(row));
    else msg.textContent = body.error.message;
  } catch (err) {
    msg.textContent = '서버에 연결할 수 없습니다.';
  }
}

document.getElementById('open-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const form = e.target;
  msg.textContent = '';
  try {
    const body = await call('POST', '/api/v1/accounts', { 'Idempotency-Key': openKey },
      { password: form.password.value });
    if (body.success) {
      openKey = newKey();
      form.reset();
      load();
    } else {
      msg.textContent = body.error.message;
    }
  } catch (err) {
    msg.textContent = '서버에 연결할 수 없습니다.';
  }
});

load();
</script>
</body>
</html>
