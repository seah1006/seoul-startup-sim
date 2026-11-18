package com.seah.startupsim;

import com.seah.startupsim.auth.AuthException;
import com.seah.startupsim.auth.AuthService;
import com.seah.startupsim.game.GuestAuth;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

/** 실제 DB에서 전환과 토큰 폐기 원자성을 확인한다. */
@Testcontainers
@SpringBootTest
class AuthIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired
    AuthService service;
    @Autowired
    GuestAuth guests;

    private String id() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private String password() {
        return UUID.randomUUID().toString() + "a1";
    }

    @Test
    void conversionAndLogout() {
        var guest = guests.create();
        var member = service.signup(id(), password(), id(), service.authenticate(guest.guestToken()));
        assertEquals(guest.userId(), member.user().userId());
        assertThrows(AuthException.class, () -> service.authenticate(guest.guestToken()));
        assertEquals(member.user().userId(), guests.resolve(member.token()));
        service.logout(service.authenticate(member.token()));
        assertThrows(AuthException.class, () -> service.authenticate(member.token()));
    }

    @Test
    void passwordRevokesOtherSessionsAndDeletionReservesId() {
        String login = id();
        String first = password();
        String second = password();
        var signup = service.signup(login, first, id(), null);
        var loginResult = service.login(login, first);
        service.changePassword(service.authenticate(signup.token()), first, second);
        assertThrows(AuthException.class, () -> service.authenticate(loginResult.token()));
        var current = service.authenticate(signup.token());
        service.delete(current, second);
        assertEquals(401, assertThrows(AuthException.class, () -> service.login(login, second)).status);
        assertEquals("LOGIN_ID_TAKEN", assertThrows(AuthException.class,
                () -> service.signup(login, second, id(), null)).code);
    }

    @Test
    void locksAtFifthFailure() {
        String login = id();
        service.signup(login, password(), id(), null);
        for (int index = 0; index < 4; index++) {
            assertEquals(401, assertThrows(AuthException.class, () -> service.login(login, password())).status);
        }
        assertEquals(423, assertThrows(AuthException.class, () -> service.login(login, password())).status);
    }
}
