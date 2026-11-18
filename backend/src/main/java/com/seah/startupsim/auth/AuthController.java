package com.seah.startupsim.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 회원 인증과 내 계정 HTTP 계약을 제공한다. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    public record Signup(String loginId, String password, String nickname) {
    }

    public record Login(String loginId, String password) {
    }

    public record Rename(String nickname) {
    }

    public record PasswordChange(String currentPassword, String newPassword) {
    }

    public record Deletion(String password) {
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthService.LoginResult signup(@RequestBody Signup request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return service.signup(request.loginId(), request.password(), request.nickname(), user);
    }

    @PostMapping("/login")
    public AuthService.LoginResult login(@RequestBody Login request) {
        return service.login(request.loginId(), request.password());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal AuthenticatedUser user) {
        service.logout(user);
    }

    @GetMapping("/me")
    public AuthService.UserMe me(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.me(user.userId());
    }

    @PatchMapping("/me")
    public AuthService.UserMe rename(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody Rename request) {
        return service.rename(user, request.nickname());
    }

    @PutMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void password(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody PasswordChange request) {
        service.changePassword(user, request.currentPassword(), request.newPassword());
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody Deletion request) {
        service.delete(user, request.password());
    }
}
