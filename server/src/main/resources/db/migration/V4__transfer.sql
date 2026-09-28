CREATE TABLE transfer_request (
    id                        BIGSERIAL PRIMARY KEY,
    request_id                VARCHAR(64)  NOT NULL UNIQUE,
    sender_member_id          BIGINT       NOT NULL REFERENCES member(id),
    from_account_number       VARCHAR(12)  NOT NULL REFERENCES account(account_number),
    recipient_account_number  VARCHAR(12)  NOT NULL REFERENCES account(account_number),
    recipient_name            VARCHAR(50)  NOT NULL,
    amount                    BIGINT       NOT NULL,
    used                      BOOLEAN      NOT NULL DEFAULT false,
    expires_at                TIMESTAMPTZ  NOT NULL,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE transfer (
    id                   BIGSERIAL PRIMARY KEY,
    from_account_number  VARCHAR(12) NOT NULL REFERENCES account(account_number),
    to_account_number    VARCHAR(12) NOT NULL REFERENCES account(account_number),
    amount               BIGINT      NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
