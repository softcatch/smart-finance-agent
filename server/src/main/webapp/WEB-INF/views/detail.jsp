<%@ page contentType="text/html;charset=UTF-8" isELIgnored="true" %>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>상세 보기</title>
<link rel="stylesheet" href="/css/smart.css">
</head>
<body>
<%@ include file="nav.jspf" %>
<main>
<h1>상세 보기</h1>
<p id="msg">보고서를 만드는 중입니다. 잠시만 기다려 주세요.</p>
<div id="report" class="report"></div>
<div id="detail-table"></div>
</main>
<script>
const token = localStorage.getItem('accessToken');
// 주소(/chat/details/{detailId})의 마지막 조각이 상세 보기 ID다.
const detailId = location.pathname.split('/').pop();
const msg = document.getElementById('msg');

// 전체 표(E-13 table). 값은 서버가 만든 문자열이라 textContent로만 넣는다.
function renderTable(t) {
  const box = document.getElementById('detail-table');
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
  box.appendChild(table);
  if (t.note) {
    const note = document.createElement('p');
    note.className = 'fin-note';
    note.textContent = t.note;
    box.appendChild(note);
  }
}

// 보고서는 클릭할 때(이 화면을 열 때) 서버가 만든다. 401이면 로그인 페이지로 보낸다.
(async () => {
  try {
    const res = await fetch('/api/v1/chat/details/' + encodeURIComponent(detailId), {
      headers: { 'Authorization': 'Bearer ' + token }
    });
    if (res.status === 401) {
      location.href = '/login';
      return;
    }
    const body = await res.json();
    if (!body.success) {
      msg.textContent = body.error.message;
      return;
    }
    msg.textContent = '';
    document.getElementById('report').textContent = body.data.report;
    if (body.data.table) renderTable(body.data.table);
  } catch (err) {
    msg.textContent = '서버에 연결할 수 없습니다.';
  }
})();
</script>
</body>
</html>
