<%@ page contentType="text/html;charset=UTF-8" %>
<!DOCTYPE html>
<html lang="ko">
<head><meta charset="UTF-8"><title>로그인</title></head>
<body>
<h1>로그인</h1>
<form id="login-form">
  <input name="loginId" placeholder="아이디" required>
  <input name="password" type="password" placeholder="비밀번호" required>
  <button type="submit">로그인</button>
</form>
<p id="msg"></p>
<a href="/signup">회원가입</a>
<script>
document.getElementById('login-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = new FormData(e.target);
  const res = await fetch('/api/v1/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ loginId: f.get('loginId'), password: f.get('password') })
  });
  const body = await res.json();
  if (body.success) {
    localStorage.setItem('accessToken', body.data.accessToken);
    document.getElementById('msg').textContent = '로그인 성공';
  } else {
    document.getElementById('msg').textContent = body.error.message;
  }
});
</script>
</body>
</html>
