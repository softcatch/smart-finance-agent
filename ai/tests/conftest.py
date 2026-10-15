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
        connection.execute("TRUNCATE financial, company")
        connection.commit()
        yield connection
