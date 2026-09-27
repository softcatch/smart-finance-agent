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

## 문서

- [개요](docs/smart-overview.md) — 목적, 로드맵, 가드레일, 테스트 전략, 아키텍처
- [요구사항·API 명세서·트래픽 추정](docs/smart-spec.xlsx)
- [기여 규칙](CONTRIBUTING.md) — 브랜치·커밋·이슈·PR
