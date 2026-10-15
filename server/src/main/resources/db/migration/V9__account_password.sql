-- 기존 계좌는 비밀번호가 없다: 빈 값이라 어떤 입력과도 맞지 않는다(송금 불가).
ALTER TABLE account ADD COLUMN password_hash VARCHAR(100) NOT NULL DEFAULT '';
ALTER TABLE account ALTER COLUMN password_hash DROP DEFAULT;
