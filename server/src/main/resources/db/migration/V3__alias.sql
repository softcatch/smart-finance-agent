CREATE TABLE alias (
    id             BIGSERIAL PRIMARY KEY,
    member_id      BIGINT      NOT NULL REFERENCES member(id),
    alias          VARCHAR(50) NOT NULL,
    account_number VARCHAR(12) NOT NULL REFERENCES account(account_number),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
