CREATE TABLE account (
    id               BIGSERIAL PRIMARY KEY,
    member_id        BIGINT       NOT NULL REFERENCES member(id),
    account_number   VARCHAR(12)  NOT NULL UNIQUE,
    idempotency_key  VARCHAR(64)  NOT NULL,
    balance          BIGINT       NOT NULL DEFAULT 0,
    is_primary       BOOLEAN      NOT NULL DEFAULT false,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (member_id, idempotency_key)
);
