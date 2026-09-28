ALTER TABLE transfer_request ADD CONSTRAINT chk_transfer_request_amount_positive CHECK (amount > 0);
ALTER TABLE transfer ADD CONSTRAINT chk_transfer_amount_positive CHECK (amount > 0);
