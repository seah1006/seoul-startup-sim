package com.seah.startupsim.auth;

import com.seah.startupsim.game.GameResponses.Guest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 가입 전환과 세션 폐기를 사용자 단위 트랜잭션으로 묶는다. */
@Service
public class AuthService {
    private final AuthRepository repository;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final String dummyHash;

    public AuthService(AuthRepository repository, PasswordEncoder encoder, Clock clock) {
        this.repository = repository;
        this.encoder = encoder;
        this.clock = clock;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    public record UserMe(long userId, String loginId, String nickname, String role, Instant createdAt) {
    }

    public record LoginResult(String token, UserMe user) {
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private AuthRepository.Account account(long id, boolean lock) {
        return repository.find("id = ? AND deleted_at IS NULL" + (lock ? " FOR UPDATE" : ""), id)
                .stream().findFirst().orElseThrow(AuthException::unauthorized);
    }

    public UserMe me(long id) {
        var user = account(id, false);
        return new UserMe(user.id(), user.loginId(), user.nickname(), user.role(), user.createdAt());
    }

    public void requireMember(long id) {
        if (!account(id, false).role().equals("MEMBER")) {
            throw new AuthException(403, "MEMBER_REQUIRED", "회원 인증이 필요합니다.");
        }
    }

    @Transactional
    public Guest createGuest() {
        String nickname = "손님-" + UUID.randomUUID();
        long id = repository.jdbc().queryForObject("""
                INSERT INTO users(nickname, role) VALUES (?, 'GUEST') RETURNING id
                """, Long.class, nickname);
        return new Guest(issue(id, "GUEST"), id, nickname);
    }

    private String issue(long userId, String kind) {
        String token = UUID.randomUUID().toString() + UUID.randomUUID();
        repository.jdbc().update("""
                INSERT INTO auth_sessions(user_id, token_hash, kind, created_at, last_used_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, userId, hash(token), kind, now(), now(),
                Timestamp.from(clock.instant().plus(Duration.ofDays(kind.equals("MEMBER") ? 14 : 30))));
        return token;
    }

    @Transactional
    public AuthenticatedUser authenticate(String token) {
        if (token == null || token.isBlank()) {
            throw AuthException.unauthorized();
        }
        String tokenHash = hash(token);
        var users = repository.jdbc().query("""
                UPDATE auth_sessions s SET last_used_at = ?,
                    expires_at = CASE WHEN s.kind = 'MEMBER' THEN ? ELSE s.expires_at END
                FROM users u WHERE s.user_id = u.id AND s.token_hash = ?
                    AND s.revoked_at IS NULL AND s.expires_at > ? AND u.deleted_at IS NULL
                RETURNING u.id, u.role
                """, (rs, row) -> new AuthenticatedUser(rs.getLong(1), rs.getString(2), tokenHash),
                now(), Timestamp.from(clock.instant().plus(Duration.ofDays(14))), tokenHash, now());
        return users.stream().findFirst().orElseThrow(AuthException::unauthorized);
    }

    @Transactional
    public LoginResult signup(String loginId, String password, String nickname, AuthenticatedUser guest) {
        validate(loginId, password, nickname);
        try {
            long id;
            if (guest != null) {
                if (!account(guest.userId(), true).role().equals("GUEST")) {
                    throw new AuthException(409, "ALREADY_MEMBER", "이미 가입한 회원입니다.");
                }
                id = guest.userId();
                repository.jdbc().update("""
                        UPDATE users SET login_id = ?, password_hash = ?, nickname = ?, role = 'MEMBER',
                            is_guest = false, updated_at = ? WHERE id = ?
                        """, loginId, encoder.encode(password), nickname, now(), id);
                revokeAll(id, null);
            } else {
                id = repository.jdbc().queryForObject("""
                        INSERT INTO users(login_id, password_hash, nickname, role, is_guest)
                        VALUES (?, ?, ?, 'MEMBER', false) RETURNING id
                        """, Long.class, loginId, encoder.encode(password), nickname);
            }
            return new LoginResult(issue(id, "MEMBER"), me(id));
        } catch (DuplicateKeyException exception) {
            throw duplicate(exception);
        }
    }

    private AuthException duplicate(DuplicateKeyException exception) {
        boolean login = exception.getMessage() != null && exception.getMessage().contains("login_id");
        return new AuthException(409, login ? "LOGIN_ID_TAKEN" : "NICKNAME_TAKEN",
                login ? "이미 사용 중인 아이디입니다." : "이미 사용 중인 닉네임입니다.");
    }

    private void validate(String login, String password, String nickname) {
        if (login == null || !login.matches("[a-z0-9_]{4,20}")) {
            throw invalid();
        }
        password(password);
        nickname(nickname);
    }

    private void password(String value) {
        if (value == null || value.length() < 8 || value.length() > 64
                || value.getBytes(StandardCharsets.UTF_8).length > 72
                || !value.matches("(?s).*[a-zA-Z].*") || !value.matches("(?s).*[0-9].*")) {
            throw invalid();
        }
    }

    private void nickname(String value) {
        if (value == null || value.isBlank() || value.length() < 2 || value.length() > 12) {
            throw invalid();
        }
    }

    private AuthException invalid() {
        return new AuthException(400, "VALIDATION_FAILED", "요청 값을 확인해 주세요.");
    }

    // 실패 카운트를 오류 응답 이후에도 보존한다.
    @Transactional(noRollbackFor = AuthException.class)
    public LoginResult login(String loginId, String password) {
        var found = repository.find("login_id = ? FOR UPDATE", loginId);
        var user = found.isEmpty() ? null : found.getFirst();
        boolean matches = encoder.matches(password == null ? "" : password,
                user == null || user.passwordHash() == null ? dummyHash : user.passwordHash());
        if (user == null || user.deletedAt() != null) {
            throw AuthException.credentials();
        }
        if (user.lockedUntil() != null && user.lockedUntil().isAfter(clock.instant())) {
            repository.jdbc().update("UPDATE users SET failed_login_count = failed_login_count + 1 WHERE id = ?", user.id());
            throw new AuthException(423, "ACCOUNT_LOCKED", "잠시 후 다시 시도해 주세요.");
        }
        if (!matches) {
            Integer count = repository.jdbc().queryForObject("""
                    UPDATE users SET failed_login_count = CASE
                        WHEN failed_login_at IS NULL OR failed_login_at <= ? THEN 1 ELSE failed_login_count + 1 END,
                        failed_login_at = CASE WHEN failed_login_at IS NULL OR failed_login_at <= ? THEN ? ELSE failed_login_at END,
                        updated_at = ? WHERE id = ? RETURNING failed_login_count
                    """, Integer.class, Timestamp.from(clock.instant().minus(Duration.ofMinutes(15))),
                    Timestamp.from(clock.instant().minus(Duration.ofMinutes(15))), now(), now(), user.id());
            if (count >= 5) {
                repository.jdbc().update("UPDATE users SET locked_until = ? WHERE id = ?",
                        Timestamp.from(clock.instant().plus(Duration.ofMinutes(15))), user.id());
                throw new AuthException(423, "ACCOUNT_LOCKED", "잠시 후 다시 시도해 주세요.");
            }
            throw AuthException.credentials();
        }
        repository.jdbc().update("""
                UPDATE users SET failed_login_count = 0, failed_login_at = NULL, locked_until = NULL WHERE id = ?
                """, user.id());
        return new LoginResult(issue(user.id(), "MEMBER"), me(user.id()));
    }

    @Transactional
    public void logout(AuthenticatedUser user) {
        repository.jdbc().update("UPDATE auth_sessions SET revoked_at = ? WHERE token_hash = ?", now(), user.tokenHash());
    }

    @Transactional
    public UserMe rename(AuthenticatedUser user, String nickname) {
        requireMember(user.userId());
        nickname(nickname);
        try {
            repository.jdbc().update("UPDATE users SET nickname = ?, updated_at = ? WHERE id = ?", nickname, now(), user.userId());
        } catch (DuplicateKeyException exception) {
            throw duplicate(exception);
        }
        return me(user.userId());
    }

    @Transactional
    public void changePassword(AuthenticatedUser user, String current, String replacement) {
        requireMember(user.userId());
        var account = account(user.userId(), true);
        if (current == null || !encoder.matches(current, account.passwordHash())) {
            throw AuthException.credentials();
        }
        password(replacement);
        repository.jdbc().update("UPDATE users SET password_hash = ?, updated_at = ? WHERE id = ?",
                encoder.encode(replacement), now(), user.userId());
        revokeAll(user.userId(), user.tokenHash());
    }

    @Transactional
    public void delete(AuthenticatedUser user, String password) {
        requireMember(user.userId());
        var account = account(user.userId(), true);
        if (password == null || !encoder.matches(password, account.passwordHash())) {
            throw AuthException.credentials();
        }
        repository.jdbc().update("""
                UPDATE users SET deleted_at = ?, nickname = '탈퇴한 사장님', updated_at = ? WHERE id = ?
                """, now(), now(), user.userId());
        revokeAll(user.userId(), null);
    }

    private void revokeAll(long id, String keep) {
        repository.jdbc().update("""
                UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL
                    AND (CAST(? AS text) IS NULL OR token_hash <> ?)
                """, now(), id, keep, keep);
    }
}

