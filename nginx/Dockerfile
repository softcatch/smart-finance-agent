# 유일한 입구(NFR-053). 설정은 환경변수로 받는다: DOMAIN(서버 이름, 필수), UPSTREAM(Spring 주소).
FROM nginx:1.30-alpine
RUN apk add --no-cache openssl
# 설정의 ${NGINX_LOCAL_RESOLVERS}에 컨테이너의 DNS(Docker)를 넣는다 — upstream 이름을 요청마다 다시 풀기 위해서다.
ENV NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1 \
    UPSTREAM=server:8080 \
    CERT_WATCH_SECONDS=60
COPY templates/ /etc/nginx/templates/
COPY docker-entrypoint.d/ /docker-entrypoint.d/
RUN chmod +x /docker-entrypoint.d/*.sh
