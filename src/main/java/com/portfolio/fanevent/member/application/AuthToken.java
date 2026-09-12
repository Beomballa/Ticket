package com.portfolio.fanevent.member.application;

public record AuthToken(
        String accessToken,
        String tokenType,
        long expiresIn
) {
}
