package com.portfolio.fanevent.member.api;

import com.portfolio.fanevent.member.application.AuthService;
import com.portfolio.fanevent.member.application.AuthToken;
import com.portfolio.fanevent.member.application.MemberProfile;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/api/auth/signup")
    ResponseEntity<MemberProfile> signup(@Valid @RequestBody SignupRequest request) {
        MemberProfile profile = authService.signup(request.email(), request.password(), request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(profile);
    }

    @PostMapping("/api/auth/login")
    AuthToken login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
    }

    @GetMapping("/api/members/me")
    MemberProfile me(@AuthenticationPrincipal Jwt jwt) {
        return authService.getProfile(Long.valueOf(jwt.getSubject()));
    }
}
