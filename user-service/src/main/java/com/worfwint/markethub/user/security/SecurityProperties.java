package com.worfwint.markethub.user.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param jwkSetUri auth-service's JWKS endpoint with the public keys for verifying access tokens
 * @param issuer expected "iss" claim
 */
@ConfigurationProperties("markethub.security")
public record SecurityProperties(String jwkSetUri, String issuer) {
}
