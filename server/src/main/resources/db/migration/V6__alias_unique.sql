ALTER TABLE alias ADD CONSTRAINT uq_alias_member_alias UNIQUE (member_id, alias);
