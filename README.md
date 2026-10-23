# SMART — smart-finance-agent

AI 채팅으로 송금하고, DART 공시를 검색·분석하는 서비스.

| 글자 | 영역 | 내용 |
|---|---|---|
| **S** | Sending | 송금 |
| **M** | Management | 송금 내역 조회 |
| **A** | AI & Analysis | LangGraph 기반 AI 분석 |
| **R** | RAG & Retrieval | DART 공시 데이터 검색·RAG |
| **T** | Transfer | 공시 데이터 전송·송금 업무 |

**기술 스택**: JSP · Spring Boot 4.1.1(Java 17, JPA, JWT) · FastAPI + LangGraph · OpenAI · PostgreSQL + pgvector · Docker Compose

## 로컬 실행

```bash
cp .env.example .env        # 비밀번호 바꾸기
docker compose up -d --build
curl localhost:8080/actuator/health
```

FastAPI(`ai`)는 내부 전용이라 포트를 열지 않는다. Spring만 `http://ai:8000`으로 호출한다.
Spring(8080)은 `127.0.0.1`에만 열린다. HTTPS 입구(nginx)는 서버 배포용이라(프로필 `proxy`) 로컬에서는 뜨지 않는다 — 입구 설정은 `nginx/test.sh`(자체 서명 인증서와 가짜 upstream)로 확인한다.

## 배포(홈서버)

`main`에 머지되면 `.github/workflows/deploy.yml`이 server·ai·proxy(nginx) 이미지(amd64·arm64)를 빌드해 Docker Hub에 올리고,
SSH로 홈서버에 접속해 `docker pull` 후 `docker compose --profile proxy up -d`로 다시 띄운다.
서버의 `/actuator/health`나 도메인으로 들어오는 입구(nginx)가 응답하지 않으면 워크플로우가 실패한다. 첫 머지 전에 아래 세 가지를 해 둔다.

### 1. GitHub 설정

저장소 Settings → Secrets and variables → Actions.

| 종류 | 이름 | 값 |
|---|---|---|
| Secret | `DOCKERHUB_USERNAME` | Docker Hub 아이디(이메일 아님). Variables에 넣어도 된다 |
| Secret | `DOCKERHUB_TOKEN` | Docker Hub → Account settings → Personal access tokens에서 만든 토큰(권한 Read & Write) |
| Secret | `SSH_HOST` | 인터넷에서 홈서버에 닿는 주소(공인 IP나 DDNS 도메인). 집 안 사설 IP는 GitHub 러너가 닿지 못한다 |
| Secret | `SSH_PORT` | `2222` |
| Secret | `SSH_USER` | 서버 계정(docker 그룹 소속) |
| Secret | `SSH_PRIVATE_KEY` | 배포 전용 개인키 파일 전체(`-----BEGIN`부터 `-----END`까지 줄바꿈 포함) |
| Secret | `SSH_HOST_KEY` | 홈서버의 호스트 공개키 한 줄. 워크플로우는 이 키와 같은 서버에만 접속한다 |

배포 전용 키는 새로 만든다.

```bash
ssh-keygen -t ed25519 -f deploy_key -N "" -C "github-actions-deploy"
# deploy_key.pub  → 서버의 ~/.ssh/authorized_keys에 한 줄 추가
# deploy_key      → SSH_PRIVATE_KEY에 붙여 넣은 뒤 로컬 파일은 지운다
```

호스트 키는 서버에서 직접 꺼낸다(네트워크로 받으면 고정하는 의미가 없다).

```bash
cat /etc/ssh/ssh_host_ed25519_key.pub   # 한 줄 전체를 SSH_HOST_KEY에 붙여 넣는다
```

ed25519 파일이 없으면 같은 폴더의 `ssh_host_ecdsa_key.pub`·`ssh_host_rsa_key.pub` 중 하나를 쓴다.
서버 OS를 다시 설치하는 등 호스트 키가 바뀌면 `SSH_HOST_KEY`도 새 값으로 바꾼다.

### 2. 서버 준비(한 번)

- Docker와 Compose v2(`docker compose version`), 위 계정의 docker 그룹 소속, 공유기의 2222(SSH)·80·443(아래 3) 포트 포워딩
- `~/smart-finance-agent/.env`를 서버에서 직접 만든다. `.env.example`을 바탕으로 DB 비밀번호·JWT 시크릿을 바꾸고 `DART_API_KEY`·`OPENAI_API_KEY`를 채운다. OpenAI 장애 때 Claude로 대체하려면 `ANTHROPIC_API_KEY`도 채운다(비우면 대체 없이 OpenAI만 쓴다). 관리자 화면(`/admin/ai-usage`)을 쓰려면 `SMART_ADMIN_LOGIN_IDS`에 관리자로 쓸 아이디를 적고 그 아이디로 가입한다(이미 가입한 회원은 DB에서 `UPDATE member SET role = 'ADMIN' WHERE login_id = '…'`). 비밀값이라 레포·GitHub에 두지 않는다.
- `compose.yaml`은 배포 때마다 워크플로우가 서버로 복사한다.
- Docker Hub 저장소가 private이면 서버에서 `docker login`을 한 번 해 둔다.

### 3. 도메인·HTTPS(nginx, 한 번)

nginx가 유일한 입구(80·443)다. Spring 8080은 서버 안(`127.0.0.1`)에서만 열려서, 이 준비가 끝나기 전에는 밖에서 접속되지 않는다.
`.env`에 아래 값이 없으면 배포가 실행 중인 컨테이너를 건드리기 전에 멈춘다(기존 배포는 그대로 돈다).

1. **DNS**: 도메인의 A 레코드를 공인 IP로 둔다(DDNS를 쓰면 그 주소를 가리키는 CNAME).
2. **공유기**: 80·443을 홈서버로 포트포워딩한다(SSH 2222는 그대로). 8080은 열지 않는다.
3. **서버 `.env`**에 추가한다.

   ```text
   DOMAIN=example.com
   LETSENCRYPT_EMAIL=me@example.com   # 인증서 만료 안내를 받을 주소
   LETSENCRYPT_STAGING=1              # 처음 시험할 때만(아래 4)
   ```

4. **첫 배포는 시험용(스테이징) 인증서로 확인한다.** Let's Encrypt는 진짜 서버에서 실패가 쌓이면 한동안 막는다. `LETSENCRYPT_STAGING=1`로 배포하고(머지, 또는 Actions의 Run workflow) 서버에서 본다.

   ```bash
   cd ~/smart-finance-agent
   docker compose --profile proxy logs certbot | tail   # "Successfully received certificate"가 보이면 발급된 것이다
   curl -skI https://도메인 | head -1                    # 시험용 인증서라 브라우저는 경고한다(-k)
   ```

   받지 못하면 로그의 메시지를 본다 — 도메인이 서버를 가리키는지(DNS), 밖에서 80번 포트로 들어오는지(포트포워딩·방화벽). 안 되는 동안에는 자체 서명 인증서로 떠 있고, 10분마다 다시 시도한다.
5. 된다면 `.env`에서 `LETSENCRYPT_STAGING` 줄을 지우고 다시 배포한다. 진짜 인증서를 새로 받는다(시험용은 이름이 달라 따로 지울 것이 없다). 인증서는 12시간마다 확인해 만료 30일 전부터 갱신하고, 바뀌면 nginx가 1분 안에 다시 읽는다.

서버에서 Spring을 직접 확인할 때는 `curl localhost:8080/actuator/health`를 쓴다(밖에서는 입구가 `/actuator`·`/internal`을 막는다).

### 롤백

이미지는 `latest`와 커밋 앞 7자리 태그로 올라간다. 되돌릴 때는 서버에서 이전 커밋의 태그를 받아 같은 이름으로 태그한다.

```bash
cd ~/smart-finance-agent
for n in server ai proxy; do
  docker pull <아이디>/smart-finance-agent-$n:<커밋 7자리>
  docker tag  <아이디>/smart-finance-agent-$n:<커밋 7자리> smart-finance-agent-$n:latest
done
docker compose --profile proxy up -d
```

## 문서

- [개요](docs/smart-overview.md) — 목적, 로드맵, 가드레일, 테스트 전략, 아키텍처
- [요구사항·API 명세서·트래픽 추정](docs/smart-spec.xlsx)
- [기여 규칙](CONTRIBUTING.md) — 브랜치·커밋·이슈·PR
