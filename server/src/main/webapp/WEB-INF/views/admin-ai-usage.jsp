<%@ page contentType="text/html;charset=UTF-8" isELIgnored="true" %>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>AI 사용량</title>
<link rel="stylesheet" href="/css/smart.css">
</head>
<body>
<%@ include file="nav.jspf" %>
<main>
<h1>AI 사용량</h1>
<form id="date-form">
  <input id="date" type="date" required>
  <button type="submit">조회</button>
</form>
<p id="limit"></p>
<table id="usage">
  <thead><tr><th>아이디</th><th>호출(대체)</th><th>입력 토큰</th><th>출력 토큰</th><th>비용(USD)</th><th>상태</th><th>정지·해제</th></tr></thead>
  <tbody></tbody>
</table>
<p id="msg"></p>
</main>
<script>
const token = localStorage.getItem('accessToken');
const dateInput = document.getElementById('date');
const tbody = document.querySelector('#usage tbody');
const limitText = document.getElementById('limit');
const msg = document.getElementById('msg');
let dailyLimit = 0;

// 서버의 하루 경계(KST)와 맞춰 오늘 날짜(yyyy-MM-dd)를 구한다.
function todayKst() {
  return new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Seoul' });
}

// 서버 응답({success, data, error})을 돌려준다. 401이면 로그인 페이지로 보낸다.
async function call(method, path, body) {
  const res = await fetch(path, {
    method,
    headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token },
    body: body && JSON.stringify(body)
  });
  if (res.status === 401) {
    location.href = '/login';
    throw new Error('unauthorized');
  }
  return res.json();
}

function row(item) {
  const tr = document.createElement('tr');
  tr.dataset.memberId = item.memberId;
  const total = item.inputTokens + item.outputTokens;
  const cells = [
    item.loginId,
    item.calls + ' (' + item.fallbackCalls + ')',
    item.inputTokens.toLocaleString('ko-KR'),
    item.outputTokens.toLocaleString('ko-KR'),
    '$' + item.costUsd.toFixed(4),
    item.blocked ? '정지' : total >= dailyLimit ? '한도 초과' : '정상'
  ];
  cells.forEach((text, i) => {
    const td = document.createElement('td');
    td.textContent = text;
    if (i === 5) td.className = 'status';
    tr.appendChild(td);
  });

  const reason = document.createElement('input');
  reason.className = 'reason';
  reason.placeholder = '사유';
  const toggle = document.createElement('button');
  toggle.className = 'toggle';
  toggle.textContent = item.blocked ? '해제' : '정지';
  // 처리 중에는 버튼을 막아 한 번만 보낸다.
  toggle.addEventListener('click', async () => {
    toggle.disabled = true;
    msg.textContent = '';
    try {
      const body = await call('PATCH', '/api/v1/admin/members/' + item.memberId + '/ai-block',
        { blocked: !item.blocked, reason: reason.value || null });
      if (body.success) load();
      else msg.textContent = body.error.message;
    } catch (err) {
      msg.textContent = '서버에 연결할 수 없습니다.';
    } finally {
      toggle.disabled = false;
    }
  });
  const action = document.createElement('td');
  action.append(reason, toggle);
  tr.appendChild(action);
  return tr;
}

async function load() {
  msg.textContent = '';
  try {
    const body = await call('GET', '/api/v1/admin/ai-usage?date=' + dateInput.value);
    if (!body.success) { // 관리자가 아니면 ADMIN_ONLY
      tbody.replaceChildren();
      limitText.textContent = '';
      msg.textContent = body.error.message;
      return;
    }
    dailyLimit = body.data.dailyLimitTokens;
    limitText.textContent = '하루 한도 ' + dailyLimit.toLocaleString('ko-KR') + '토큰(입력+출력, 한국 시간 자정에 풀립니다)';
    tbody.replaceChildren(...body.data.items.map(row));
    if (body.data.items.length === 0) msg.textContent = '이 날 사용한 회원이 없습니다.';
  } catch (err) {
    msg.textContent = '서버에 연결할 수 없습니다.';
  }
}

document.getElementById('date-form').addEventListener('submit', (e) => {
  e.preventDefault();
  load();
});

dateInput.value = todayKst();
load();
</script>
</body>
</html>
