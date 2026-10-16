<%@ page contentType="text/html;charset=UTF-8" isELIgnored="true" %>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>채팅</title>
<link rel="stylesheet" href="/css/smart.css">
</head>
<body class="chat">
<%@ include file="nav.jspf" %>
<main>
<div class="chat-head">
  <h1>채팅</h1>
  <button id="new-chat" type="button">새 대화</button>
</div>
<div id="log"></div>
<p id="msg"></p>
<form id="chat-form">
  <input name="message" placeholder="메시지" autocomplete="off" required>
  <button type="submit">전송</button>
</form>
</main>
<script>
const token = localStorage.getItem('accessToken');
const headers = { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token };
const log = document.getElementById('log');
const msg = document.getElementById('msg');

// 대화는 이 탭의 sessionStorage에 둔다 — 메뉴를 다녀와도 같은 대화를 잇고, 탭을 닫거나 로그아웃하면 사라진다.
// items: [{ who, text, card?, table?, result?, done? }] — result·done은 확인 카드의 결과 표시다.
let state = JSON.parse(sessionStorage.getItem('chat')) || { threadId: null, items: [] };

function save() {
  sessionStorage.setItem('chat', JSON.stringify(state));
}

function push(item) {
  state.items.push(item);
  save();
  render(item);
}

// 서버 응답({success, data, error})을 돌려준다. 401이면 로그인 페이지로 보낸다.
async function call(path, body) {
  const res = await fetch(path, { method: 'POST', headers, body: JSON.stringify(body || {}) });
  if (res.status === 401) {
    location.href = '/login';
    throw new Error('unauthorized');
  }
  return res.json();
}

// 말풍선 한 줄. 내 메시지(me)는 오른쪽, AI(ai)는 왼쪽에 붙는다(CSS). 확인 카드·표는 AI 말풍선 아래에 붙는다.
function render(item) {
  const row = document.createElement('div');
  row.className = 'msg msg-' + item.who;
  if (item.who === 'ai') {
    const name = document.createElement('span');
    name.className = 'who';
    name.textContent = 'AI';
    row.appendChild(name);
  }
  const bubble = document.createElement('div');
  bubble.className = 'bubble';
  bubble.textContent = item.text;
  row.appendChild(bubble);
  if (item.card) addCard(row, item);
  if (item.table) addTable(row, item.table);
  log.appendChild(row);
  log.scrollTop = log.scrollHeight;
}

// 송금 확인 카드(FR-009). [보내기]는 AI 경로를 거치지 않고 화면이 Spring에 직접 확정한다.
// 요청ID는 1회용이다 — 성공하거나 비밀번호가 틀리면(폐기됨) 다시 누를 수 없게 막는다.
// 결과는 item에 남겨 두어, 메뉴를 다녀온 뒤에도 끝난 카드가 다시 눌리지 않는다. 비밀번호는 저장하지 않는다.
function addCard(row, item) {
  const card = item.card;
  const box = document.createElement('div');
  box.className = 'transfer-card';

  const info = document.createElement('p');
  info.textContent = '받는 분: ' + card.recipientName + '(' + card.recipientAccountNumber + ')'
    + ' / 금액: ' + card.amount.toLocaleString('ko-KR') + '원'
    + ' / 출금 계좌: ' + card.fromAccountNumber;

  const password = document.createElement('input');
  password.type = 'password';
  password.name = 'password';
  password.placeholder = '계좌 비밀번호';

  const button = document.createElement('button');
  button.textContent = '보내기';
  button.disabled = !!item.done;

  const result = document.createElement('p');
  result.className = 'card-result';
  result.textContent = item.result || '';

  button.addEventListener('click', async () => {
    button.disabled = true;
    try {
      const body = await call('/api/v1/transfer-requests/' + card.requestId + '/confirm',
        { password: password.value });
      if (body.success) {
        item.result = '송금 완료: ' + body.data.recipientName + '님께 '
          + body.data.amount.toLocaleString('ko-KR') + '원, 잔액 '
          + body.data.balanceAfter.toLocaleString('ko-KR') + '원';
        item.done = true;
      } else if (body.error.code === 'ACCOUNT_PASSWORD_INVALID') {
        item.result = body.error.message
          + ' 이 송금 요청은 취소되었습니다. 채팅으로 다시 요청해 주세요.';
        item.done = true;
      } else {
        item.result = body.error.message;
        button.disabled = false;
      }
    } catch (err) {
      item.result = '서버에 연결할 수 없습니다.';
      button.disabled = false;
    }
    result.textContent = item.result;
    save();
  });

  box.append(info, password, button, result);
  row.appendChild(box);
}

// 재무 비교 표(E-11 table). 값은 서버가 만든 문자열이라 textContent로만 넣는다.
function addTable(row, t) {
  const table = document.createElement('table');
  table.className = 'fin-table';
  const head = document.createElement('thead');
  const headRow = document.createElement('tr');
  t.columns.forEach((text) => {
    const th = document.createElement('th');
    th.textContent = text;
    headRow.appendChild(th);
  });
  head.appendChild(headRow);
  const body = document.createElement('tbody');
  t.rows.forEach((cells) => {
    const tr = document.createElement('tr');
    cells.forEach((text) => {
      const td = document.createElement('td');
      td.textContent = text;
      tr.appendChild(td);
    });
    body.appendChild(tr);
  });
  table.append(head, body);
  row.appendChild(table);
  if (t.note) {
    const note = document.createElement('p');
    note.className = 'fin-note';
    note.textContent = t.note;
    row.appendChild(note);
  }
}

// 저장된 대화가 있으면 그 threadId를 잇고, 없으면 새 대화를 만든다(FR-013). 전송은 이 약속이 끝난 뒤에 한다.
// 응답은 요청을 보낸 그 대화에만 적는다 — 먼저 보낸 요청이 늦게 끝나도 [새 대화]의 threadId를 덮어쓰지 않는다.
function newThread() {
  const chat = state;
  return call('/api/v1/chat/threads').then((body) => {
    if (!body.success) throw new Error(body.error.message);
    chat.threadId = body.data.threadId;
    if (state === chat) save();
    return chat.threadId;
  });
}
let ready = state.threadId ? Promise.resolve(state.threadId) : newThread();
state.items.forEach(render);

document.getElementById('new-chat').addEventListener('click', () => {
  state = { threadId: null, items: [] };
  log.replaceChildren();
  msg.textContent = '';
  ready = newThread();
});

document.getElementById('chat-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const input = e.target.message;
  const button = e.target.querySelector('button');
  const text = input.value;
  input.value = '';
  button.disabled = true;
  msg.textContent = '';
  const chat = state;
  push({ who: 'me', text });
  try {
    const threadId = await ready;
    const body = await call('/api/v1/chat/threads/' + threadId + '/messages', { message: text });
    if (state !== chat) return; // 답을 기다리는 사이 [새 대화]를 눌렀다 — 새 대화에 섞지 않는다
    if (body.success) {
      push({ who: 'ai', text: body.data.reply, card: body.data.transferCard, table: body.data.table });
    } else {
      msg.textContent = body.error.message;
    }
  } catch (err) {
    msg.textContent = '서버에 연결할 수 없습니다.';
  } finally {
    button.disabled = false;
  }
});
</script>
</body>
</html>
