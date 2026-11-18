ALTER TABLE users ADD COLUMN login_id text UNIQUE,
    ADD COLUMN password_hash text,
    ADD COLUMN deleted_at timestamptz,
    ADD COLUMN failed_login_count int NOT NULL DEFAULT 0,
    ADD COLUMN failed_login_at timestamptz,
    ADD COLUMN locked_until timestamptz,
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
UPDATE users SET role = 'GUEST', is_guest = true;
ALTER TABLE users ALTER COLUMN role SET DEFAULT 'GUEST';
ALTER TABLE users ADD CONSTRAINT users_role CHECK (role IN ('GUEST', 'MEMBER')),
    ADD CONSTRAINT users_member_credentials CHECK (role <> 'MEMBER' OR (login_id IS NOT NULL AND password_hash IS NOT NULL));
-- 기존 게스트 닉네임이 중복되면 모두 고유한 사용자 ID 접미사를 붙인다.
WITH duplicates AS (SELECT nickname FROM users GROUP BY nickname HAVING count(*) > 1)
UPDATE users SET nickname = nickname || '-' || id WHERE nickname IN (SELECT nickname FROM duplicates);
CREATE UNIQUE INDEX users_active_nickname ON users(nickname) WHERE deleted_at IS NULL;
CREATE TABLE auth_sessions (
    id bigserial PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(id),
    token_hash char(64) NOT NULL UNIQUE,
    kind text NOT NULL CHECK (kind IN ('GUEST', 'MEMBER')),
    created_at timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz
);
CREATE INDEX auth_sessions_active_user ON auth_sessions(user_id) WHERE revoked_at IS NULL;
INSERT INTO auth_sessions(user_id, token_hash, kind, expires_at)
SELECT id, guest_token_hash, 'GUEST', now() + interval '30 days' FROM users WHERE guest_token_hash IS NOT NULL;
-- 롤백 호환을 위해 컬럼은 남기지만 인증은 auth_sessions만 사용한다. 다음 정리 작업에서 제거한다.
