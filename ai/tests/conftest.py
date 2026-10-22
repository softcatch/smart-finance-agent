import psycopg
import pytest
from testcontainers.community.postgres import PostgresContainer


# 실제 PostgreSQL(compose와 같은 pgvector 이미지)로 SQL을 검증한다 — 가짜 DB를 쓰지 않는다.
@pytest.fixture(scope="session")
def database_url():
    with PostgresContainer("pgvector/pgvector:pg17", driver=None) as pg:
        yield pg.get_connection_url()


# 테스트마다 비어 있는 테이블에서 시작한다.
@pytest.fixture
def conn(database_url):
    from app.store import ensure_schema

    with psycopg.connect(database_url) as connection:
        ensure_schema(connection)
        connection.execute(
            "TRUNCATE financial, company, business_chunk, chat_log, detail_condition, ai_usage"
        )
        connection.commit()
        yield connection


# 채팅 엔드포인트 테스트가 개발 DB에 로그를 쓰지 않게 막는다. 로그를 확인하는 테스트는 이 값을 다시 바꾼다.
@pytest.fixture(autouse=True)
def no_chat_log(monkeypatch):
    monkeypatch.setattr("app.main.log_chat", lambda *args: None)


# 환경변수에 개발 DB 접속 정보가 있어도 테스트가 거기에 쓰지 않게 막는다. DB가 필요한 테스트는 이 값을 다시 바꾼다.
@pytest.fixture(autouse=True)
def no_dev_db(monkeypatch):
    def refuse():
        raise psycopg.OperationalError("테스트는 개발 DB에 연결하지 않는다")

    monkeypatch.setattr("app.main._connect", refuse)
