<%@ page contentType="text/html;charset=UTF-8" isELIgnored="true" %>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>별칭</title>
<link rel="stylesheet" href="/css/smart.css">
</head>
<body>
<%@ include file="nav.jspf" %>
<main>
<h1>별칭</h1>
<table id="aliases">
  <thead><tr><th>별칭</th><th>계좌번호</th><th>예금주</th></tr></thead>
  <tbody></tbody>
</table>
<h2>별칭 등록</h2>
<form id="alias-form">
  <input name="alias" placeholder="별칭(예: 엄마)" required>
  <input name="accountNumber" placeholder="계좌번호" required>
  <button type="submit">등록</button>
</form>
<p id="msg"></p>
</main>
<script>
const token = localStorage.getItem('accessToken');
const tbody = document.querySelector('#aliases tbody');
const msg = document.getElementById('msg');

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

function row(alias) {
  const tr = document.createElement('tr');
  [alias.alias, alias.accountNumber, alias.ownerName].forEach((text) => {
    const td = document.createElement('td');
    td.textContent = text;
    tr.appendChild(td);
  });
  return tr;
}

async function load() {
  try {
    const body = await call('GET', '/api/v1/aliases');
    if (body.success) tbody.replaceChildren(...body.data.aliases.map(row));
    else msg.textContent = body.error.message;
  } catch (err) {
    msg.textContent = '서버에 연결할 수 없습니다.';
  }
}

document.getElementById('alias-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const form = e.target;
  const button = form.querySelector('button');
  button.disabled = true;
  msg.textContent = '';
  try {
    const body = await call('POST', '/api/v1/aliases',
      { alias: form.alias.value, accountNumber: form.accountNumber.value });
    if (body.success) {
      form.reset();
      load();
    } else {
      msg.textContent = body.error.message;
    }
  } catch (err) {
    msg.textContent = '서버에 연결할 수 없습니다.';
  } finally {
    button.disabled = false;
  }
});

load();
</script>
</body>
</html>
