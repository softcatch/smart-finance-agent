#!/bin/sh
# 인증서가 아직 없으면 자체 서명 인증서를 둔다. nginx가 켜져야 Let's Encrypt가 80번 포트로 도메인을 확인할 수 있다.
# 진짜 인증서가 발급되면 certbot이 같은 자리(/certs)를 덮어쓰고, 50번 스크립트가 바뀐 것을 보고 다시 읽는다.
set -eu
mkdir -p /certs
if [ ! -s /certs/fullchain.pem ] || [ ! -s /certs/privkey.pem ]; then
  echo "인증서가 없어 자체 서명 인증서로 시작한다: ${DOMAIN}"
  openssl req -x509 -nodes -newkey rsa:2048 -days 30 -subj "/CN=${DOMAIN}" \
    -keyout /certs/privkey.pem -out /certs/fullchain.pem
fi
