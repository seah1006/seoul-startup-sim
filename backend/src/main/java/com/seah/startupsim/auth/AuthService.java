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
        return repository.findById(id, lock)
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
        long id = repository.createGuest(nickname);
        return new Guest(issue(id, "GUEST"), id, nickname);
    }

    private String issue(long userId, String kind) {
        String token = UUID.randomUUID().toString() + UUID.randomUUID();
        repository.createSession(userId, hash(token), kind, now(), Timestamp.from(clock.instant().plus(Duration.ofDays(kind.equals("MEMBER") ? 14 : 30))));
        return token;
    }

    @Transactional
    public AuthenticatedUser authenticate(String token) {
        if (token == null || token.isBlank()) {
            throw AuthException.unauthorized();
        }
        String tokenHash = hash(token);
        var users = repository.authenticate(tokenHash, now(), Timestamp.from(clock.instant().plus(Duration.ofDays(14))));
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
                repository.convert(id, loginId, encoder.encode(password), nickname, now());
                revokeAll(id, null);
            } else {
                id = repository.createMember(loginId, encoder.encode(password), nickname);
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

    private boolean matches(String value, String encoded) {
        if (value != null && value.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw invalid();
        }
        return encoder.matches(value == null ? "" : value, encoded);
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
        var found = repository.findByLoginForUpdate(loginId);
        var user = found.isEmpty() ? null : found.getFirst();
        boolean matches = matches(password,
                user == null || user.passwordHash() == null ? dummyHash : user.passwordHash());
        if (user == null || user.deletedAt() != null) {
            throw AuthException.credentials();
        }
        if (user.lockedUntil() != null && user.lockedUntil().isAfter(clock.instant())) {
            repository.countLockedAttempt(user.id());
            throw new AuthException(423, "ACCOUNT_LOCKED", "잠시 후 다시 시도해 주세요.");
        }
        if (!matches) {
            Integer count = repository.failLogin(user.id(), Timestamp.from(clock.instant().minus(Duration.ofMinutes(15))), now());
            if (count >= 5) {
                repository.lock(user.id(), Timestamp.from(clock.instant().plus(Duration.ofMinutes(15))));
                throw new AuthException(423, "ACCOUNT_LOCKED", "잠시 후 다시 시도해 주세요.");
            }
            throw AuthException.credentials();
        }
        repository.clearFailures(user.id());
        return new LoginResult(issue(user.id(), "MEMBER"), me(user.id()));
    }

    @Transactional
    public void logout(AuthenticatedUser user) {
        repository.revoke(user.tokenHash(), now());
    }

    @Transactional
    public UserMe rename(AuthenticatedUser user, String nickname) {
        requireMember(user.userId());
        nickname(nickname);
        try {
            repository.rename(user.userId(), nickname, now());
        } catch (DuplicateKeyException exception) {
            throw duplicate(exception);
        }
        return me(user.userId());
    }

    @Transactional
    public void changePassword(AuthenticatedUser user, String current, String replacement) {
        requireMember(user.userId());
        var account = account(user.userId(), true);
        if (current == null || !matches(current, account.passwordHash())) {
            throw AuthException.credentials();
        }
        password(replacement);
        repository.changePassword(user.userId(), encoder.encode(replacement), now());
        revokeAll(user.userId(), user.tokenHash());
    }

    @Transactional
    public void delete(AuthenticatedUser user, String password) {
        requireMember(user.userId());
        var account = account(user.userId(), true);
        if (password == null || !matches(password, account.passwordHash())) {
            throw AuthException.credentials();
        }
        repository.delete(user.userId(), now());
        revokeAll(user.userId(), null);
    }

    private void revokeAll(long id, String keep) {
        repository.revokeAll(id, keep, now());
    }
}

