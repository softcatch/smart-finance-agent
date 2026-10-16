<%@ page contentType="text/html;charset=UTF-8" isELIgnored="true" %>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>송금 내역</title>
<link rel="stylesheet" href="/css/smart.css">
</head>
<body>
<%@ include file="nav.jspf" %>
<main>
<h1>송금 내역</h1>
<table id="transfers">
  <thead><tr><th>일시</th><th>구분</th><th>상대</th><th>금액</th></tr></thead>
  <tbody></tbody>
</table>
<div id="sentinel" style="height: 1px"></div>
<p id="msg"></p>
</main>
<script>
const token = localStorage.getItem('accessToken');
const tbody = document.querySelector('#transfers tbody');
const sentinel = document.getElementById('sentinel');
const msg = document.getElementById('msg');

let cursor = null; // 다음 묶음의 시작 위치(응답의 nextCursor)
let hasNext = true;
let loading = false;

function row(item) {
  const tr = document.createElement('tr');
  const cells = [
    // 서버의 날짜 경계(KST)와 맞춰 한국 시간으로 보여 준다.
    new Date(item.createdAt).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' }),
    item.direction === 'SENT' ? '보냄' : '받음',
    item.counterpartName + '(' + item.counterpartAccountNumber + ')',
    item.amount.toLocaleString('ko-KR') + '원'
  ];
  cells.forEach((text) => {
    const td = document.createElement('td');
    td.textContent = text;
    tr.appendChild(td);
  });
  return tr;
}

// 무한 스크롤: 목록 끝의 감시 요소가 화면에 보이면 다음 묶음(커서 기반)을 불러온다.
async function loadMore() {
  if (loading || !hasNext) return;
  loading = true;
  try {
    const res = await fetch('/api/v1/transfers' + (cursor ? '?cursor=' + cursor : ''),
      { headers: { 'Authorization': 'Bearer ' + token } });
    if (res.status === 401) {
      location.href = '/login';
      return;
    }
    const body = await res.json();
    if (!body.success) {
      msg.textContent = body.error.message;
      return;
    }
    body.data.items.forEach((item) => tbody.appendChild(row(item)));
    cursor = body.data.nextCursor;
    hasNext = body.data.hasNext;
    if (tbody.children.length === 0) msg.textContent = '송금 내역이 없습니다.';
  } catch (err) {
    msg.textContent = '서버에 연결할 수 없습니다.';
    return;
  } finally {
    loading = false;
  }
  // 옵저버는 화면에 보이는 상태가 바뀔 때만 알려 준다 — 아직 보이면 다시 걸어 한 번 더 부른다.
  if (hasNext) {
    observer.unobserve(sentinel);
    observer.observe(sentinel);
  }
}

const observer = new IntersectionObserver((entries) => {
  if (entries[0].isIntersecting) loadMore();
});
observer.observe(sentinel);
</script>
</body>
</html>
