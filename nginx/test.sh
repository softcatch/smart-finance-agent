#!/usr/bin/env bash
# nginx 설정 검증(NFR-053). 자체 서명 인증서와 가짜 upstream으로 입구의 동작을 확인한다. Docker·curl·openssl·python3가 있으면 된다.
# 사용: nginx/test.sh   (어느 디렉터리에서든). 실패가 있으면 종료 코드 1.
# Let's Encrypt 발급·갱신은 공인 DNS와 80번 포트가 필요해서 여기서 확인하지 않는다(서버의 첫 배포 때 본다).
set -uo pipefail
cd "$(dirname "$0")"

IMAGE=smart-finance-agent-proxy:test
NET=proxy-test-net
UP=proxy-test-upstream
NGX=proxy-test-nginx
HTTPS=https://localhost:18443
HTTP=http://localhost:18080
FAILED=0

cleanup() { docker rm -f $NGX $UP ${UP}-2 >/dev/null 2>&1; docker network rm $NET >/dev/null 2>&1; }
trap cleanup EXIT
cleanup

ok() { echo "  ✓ $1"; }
bad() { echo "  ✗ $1"; FAILED=$((FAILED + 1)); }
check() { # check "설명" 조건(0이면 통과)
  if [ "$2" = 0 ]; then ok "$1"; else bad "$1"; fi
}
code() { curl -sk -o /dev/null -w '%{http_code}' "$@"; }
upstream() { # upstream <이름> [별칭]: 가짜 upstream을 띄운다
  docker run -d --name "$1" --network $NET --network-alias $UP -v "$PWD/test/upstream.py:/upstream.py:ro" \
    python:3-alpine python -u /upstream.py >/dev/null
}
# 스트림의 첫 글자가 올 때까지 걸린 초(정수). 버퍼링되면 응답이 끝날 때까지(약 5초) 기다린다.
first_byte_seconds() {
  local start=$SECONDS
  curl -sk -N --max-time 10 "$1" | head -c 1 >/dev/null
  echo $((SECONDS - start))
}

echo "이미지를 만든다"
docker build -q -t $IMAGE . >/dev/null || { echo "이미지 빌드 실패"; exit 1; }
docker network create $NET >/dev/null
upstream $UP
docker run -d --name $NGX --network $NET -e DOMAIN=localhost -e UPSTREAM=$UP:8080 -e CERT_WATCH_SECONDS=1 \
  -p 127.0.0.1:18080:80 -p 127.0.0.1:18443:443 $IMAGE >/dev/null

for _ in $(seq 50); do [ "$(code $HTTPS/echo)" = 200 ] && break; sleep 0.3; done
[ "$(code $HTTPS/echo)" = 200 ] || { echo "nginx가 뜨지 않았다"; docker logs $NGX 2>&1 | tail -20; exit 1; }

echo "리다이렉트·인증서 발급 경로"
loc=$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' "$HTTP/foo?x=1")
check "80은 https로 301(경로·쿼리 유지): $loc" $([ "$loc" = "301 https://localhost/foo?x=1" ]; echo $?)
docker exec $NGX sh -c 'mkdir -p /var/www/certbot/.well-known/acme-challenge && echo token-ok > /var/www/certbot/.well-known/acme-challenge/tok'
acme=$(curl -s "$HTTP/.well-known/acme-challenge/tok")
check "인증서 발급(HTTP-01) 경로는 80에서 그대로 준다(리다이렉트 없음): $acme" $([ "$acme" = token-ok ] && [ "$(code $HTTP/.well-known/acme-challenge/tok)" = 200 ]; echo $?)

echo "프록시 헤더"
echo_json=$(curl -sk -H 'X-Forwarded-For: 6.6.6.6' "$HTTPS/echo/headers")
proto=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["headers"].get("x-forwarded-proto"))' <<<"$echo_json")
xff=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["headers"].get("x-forwarded-for"))' <<<"$echo_json")
host=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["headers"].get("host"))' <<<"$echo_json")
check "X-Forwarded-Proto는 https: $proto" $([ "$proto" = https ]; echo $?)
check "X-Forwarded-For는 클라이언트가 보낸 값이 아니라 실제 접속 IP: $xff" $([ -n "$xff" ] && [ "$xff" != "6.6.6.6" ] && [[ "$xff" != *6.6.6.6* ]]; echo $?)
check "Host는 포트 없이 도메인: $host" $([ "$host" = localhost ]; echo $?)

echo "차단 경로"
for p in /internal/transfer-requests /internal /actuator/health /actuator; do
  check "$p → 404" $([ "$(code $HTTPS$p)" = 404 ]; echo $?)
done
check "/internalx는 막지 않는다(정확히 /internal과 그 아래만)" $([ "$(code $HTTPS/internalx)" = 200 ]; echo $?)

echo "스트림 버퍼링"
fast=$(first_byte_seconds $HTTPS/api/v1/notifications/stream)
slow=$(first_byte_seconds $HTTPS/slow-stream)
check "알림 스트림은 버퍼링 없이 바로 온다(첫 글자까지 ${fast}초)" $([ "$fast" -le 2 ]; echo $?)
check "대조: 같은 응답도 일반 경로는 버퍼에 쌓였다가 끝난 뒤 온다(${slow}초)" $([ "$slow" -ge 4 ]; echo $?)

echo "읽기 제한 설정"
conf=$(docker exec $NGX nginx -T 2>/dev/null)
check "알림 스트림: 버퍼링 끄고 읽기 제한 120초(하트비트 15초보다 길다)" \
  $(grep -A5 'location = /api/v1/notifications/stream' <<<"$conf" | grep -q 'proxy_buffering off' && grep -A5 'location = /api/v1/notifications/stream' <<<"$conf" | grep -q 'proxy_read_timeout 120s'; echo $?)
check "일반 요청: 읽기 제한 60초(채팅 10초·상세 30초보다 길다)" \
  $(grep -A3 'location / {' <<<"$conf" | grep -q 'proxy_read_timeout 60s'; echo $?)

echo "upstream이 다시 만들어져 IP가 바뀌어도 따라간다"
first=$(curl -sk $HTTPS/echo/who | python3 -c 'import json,sys; print(json.load(sys.stdin)["host"])')
upstream ${UP}-2
docker rm -f $UP >/dev/null
second=""
for _ in $(seq 40); do # 이름 캐시(10초)가 지나면 새 upstream으로 간다
  second=$(curl -sk $HTTPS/echo/who 2>/dev/null | python3 -c 'import json,sys; print(json.load(sys.stdin)["host"])' 2>/dev/null || true)
  [ -n "$second" ] && [ "$second" != "$first" ] && break
  sleep 0.5
done
check "nginx를 다시 띄우지 않고 새 upstream으로 간다(${first} → ${second})" $([ -n "$second" ] && [ "$second" != "$first" ]; echo $?)

echo "인증서가 바뀌면 nginx가 다시 읽는다"
docker exec $NGX sh -c 'openssl req -x509 -nodes -newkey rsa:2048 -days 1 -subj "/CN=reloaded.test" -keyout /certs/privkey.pem.new -out /certs/fullchain.pem.new 2>/dev/null \
  && mv /certs/privkey.pem.new /certs/privkey.pem && mv /certs/fullchain.pem.new /certs/fullchain.pem'
subject=""
for _ in $(seq 30); do
  subject=$(openssl s_client -connect localhost:18443 -servername localhost </dev/null 2>/dev/null | openssl x509 -noout -subject 2>/dev/null || true)
  [[ "$subject" == *reloaded.test* ]] && break
  sleep 0.5
done
check "새 인증서를 내보낸다: $subject" $([[ "$subject" == *reloaded.test* ]]; echo $?)

echo "로그인·가입 요청 제한(마지막에 본다 — 이 IP의 허용량을 쓴다)"
statuses=""
for _ in $(seq 20); do statuses="$statuses $(code -X POST $HTTPS/api/v1/auth/login)"; done
ok200=$(tr ' ' '\n' <<<"$statuses" | grep -c '^200$')
ok429=$(tr ' ' '\n' <<<"$statuses" | grep -c '^429$')
check "연속 20번 중 처음 6~7번만 통과하고 나머지는 429(통과 $ok200, 429 $ok429)" $([ "$ok200" -ge 6 ] && [ "$ok200" -le 7 ] && [ "$ok429" -ge 13 ]; echo $?)
limited=$(curl -sk -i -X POST $HTTPS/api/v1/auth/signup)
check "429는 앱과 같은 JSON 형식(RATE_LIMITED)" $(grep -qi 'content-type: application/json' <<<"$limited" && grep -q '"code":"RATE_LIMITED"' <<<"$limited"; echo $?)
check "제한은 로그인·가입에만 건다(다른 경로는 그대로 200)" $([ "$(code $HTTPS/echo/other)" = 200 ]; echo $?)

echo
if [ $FAILED -eq 0 ]; then echo "모두 통과"; else echo "실패 $FAILED건"; docker logs $NGX 2>&1 | tail -15; exit 1; fi
