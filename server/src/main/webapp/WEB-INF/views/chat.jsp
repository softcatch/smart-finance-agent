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
    if (body.success) add('AI', body.data.reply);
    else msg.textContent = body.error.message;
  } catch (err) {
    msg.textContent = '서버에 연결할 수 없습니다.';
  } finally {
    button.disabled = false;
  }
});
</script>
</body>
</html>
