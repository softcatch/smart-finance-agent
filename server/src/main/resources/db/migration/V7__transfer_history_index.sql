CREATE INDEX idx_transfer_from_account_id ON transfer (from_account_number, id DESC);
CREATE INDEX idx_transfer_to_account_id ON transfer (to_account_number, id DESC);
