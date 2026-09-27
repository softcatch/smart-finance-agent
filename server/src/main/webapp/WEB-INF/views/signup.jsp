<%@ page contentType="text/html;charset=UTF-8" %>
<!DOCTYPE html>
<html lang="ko">
<head><meta charset="UTF-8"><title>회원가입</title></head>
<body>
<h1>회원가입</h1>
<form id="signup-form">
  <input name="loginId" placeholder="아이디" required>
  <input name="password" type="password" placeholder="비밀번호" required>
  <input name="name" placeholder="이름" required>
  <button type="submit">가입</button>
</form>
<p id="msg"></p>
<a href="/login">로그인</a>
<script>
document.getElementById('signup-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = new FormData(e.target);
  const res = await fetch('/api/v1/auth/signup', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ loginId: f.get('loginId'), password: f.get('password'), name: f.get('name') })
  });
  const body = await res.json();
  if (body.success) {
    location.href = '/login';
  } else {
    document.getElementById('msg').textContent = body.error.message;
  }
});
</script>
</body>
</html>
