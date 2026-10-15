<%@ page contentType="text/html;charset=UTF-8" isELIgnored="true" %>
<!DOCTYPE html>
<html lang="ko">
<head><meta charset="UTF-8"><title>채팅</title></head>
<body>
<h1>채팅</h1>
<div id="log" style="white-space: pre-wrap"></div>
<form id="chat-form">
  <input name="message" placeholder="메시지" autocomplete="off" required>
  <button type="submit">전송</button>
</form>
<p id="msg"></p>
<script>
const token = localStorage.getItem('accessToken');
const headers = { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token };
const log = document.getElementById('log');
const msg = document.getElementById('msg');

// 서버 응답({success, data, error})을 돌려준다. 401이면 로그인 페이지로 보낸다.
async function call(path, body) {
  const res = await fetch(path, { method: 'POST', headers, body: JSON.stringify(body || {}) });
  if (res.status === 401) {
    location.href = '/login';
    throw new Error('unauthorized');
  }
  return res.json();
}

function add(who, text) {
  const p = document.createElement('p');
  p.textContent = who + ': ' + text;
  log.appendChild(p);
}

// 송금 확인 카드(FR-009). [보내기]는 AI 경로를 거치지 않고 화면이 Spring에 직접 확정한다.
// 요청ID는 1회용이다 — 성공하거나 비밀번호가 틀리면(폐기됨) 다시 누를 수 없게 막는다.
function addCard(card) {
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

  const result = document.createElement('p');
  result.className = 'card-result';

  button.addEventListener('click', async () => {
    button.disabled = true;
    try {
      const body = await call('/api/v1/transfer-requests/' + card.requestId + '/confirm',
        { password: password.value });
      if (body.success) {
        result.textContent = '송금 완료: ' + body.data.recipientName + '님께 '
          + body.data.amount.toLocaleString('ko-KR') + '원, 잔액 '
          + body.data.balanceAfter.toLocaleString('ko-KR') + '원';
      } else if (body.error.code === 'ACCOUNT_PASSWORD_INVALID') {
        result.textContent = body.error.message
          + ' 이 송금 요청은 취소되었습니다. 채팅으로 다시 요청해 주세요.';
      } else {
        result.textContent = body.error.message;
        button.disabled = false;
      }
    } catch (err) {
      result.textContent = '서버에 연결할 수 없습니다.';
      button.disabled = false;
    }
  });

  box.append(info, password, button, result);
  log.appendChild(box);
}

// 페이지를 열 때 새 대화를 만든다(FR-013). 전송은 이 약속이 끝난 뒤에 한다.
const ready = call('/api/v1/chat/threads').then((body) => {
  if (!body.success) throw new Error(body.error.message);
  return body.data.threadId;
});

document.getElementById('chat-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const input = e.target.message;
  const button = e.target.querySelector('button');
  const text = input.value;
  input.value = '';
  button.disabled = true;
  msg.textContent = '';
  add('나', text);
  try {
    const threadId = await ready;
    const body = await call('/api/v1/chat/threads/' + threadId + '/messages', { message: text });
    if (body.success) {
      add('AI', body.data.reply);
      if (body.data.transferCard) addCard(body.data.transferCard);
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
