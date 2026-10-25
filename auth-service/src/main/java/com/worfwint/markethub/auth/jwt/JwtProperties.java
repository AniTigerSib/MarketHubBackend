package com.worfwint.markethub.auth.jwt;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("markethub.jwt")
public record JwtProperties(String issuer, Duration accessTokenTtl, Duration refreshTokenTtl) {
}
