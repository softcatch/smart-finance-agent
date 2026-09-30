CREATE TABLE chat_thread (
    id          BIGSERIAL PRIMARY KEY,
    thread_id   VARCHAR(64) NOT NULL UNIQUE,
    member_id   BIGINT      NOT NULL REFERENCES member(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
