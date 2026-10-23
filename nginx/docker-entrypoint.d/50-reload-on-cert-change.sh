#!/bin/sh
# 인증서 파일이 바뀌면(발급·갱신) nginx가 다시 읽게 한다. nginx는 시작할 때만 인증서를 읽는다.
# 배경에서 돌고, 입력은 닫아 둔다(진입 스크립트의 파이프를 붙잡지 않게).
(
  last=$(cksum /certs/fullchain.pem)
  while true; do
    sleep "${CERT_WATCH_SECONDS:-60}"
    now=$(cksum /certs/fullchain.pem)
    if [ "$now" != "$last" ]; then
      echo "인증서가 바뀌어 nginx를 다시 읽는다"
      nginx -s reload && last=$now
    fi
  done
) </dev/null &
