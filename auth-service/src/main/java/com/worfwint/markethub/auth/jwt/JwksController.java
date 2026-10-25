package com.worfwint.markethub.auth.jwt;

import java.time.Duration;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the public signing keys. Other services fetch this (directly, not through the
 * gateway) and verify access tokens locally, without calling auth-service per request.
 */
@RestController
public class JwksController {

	private final SigningKeys signingKeys;

	public JwksController(SigningKeys signingKeys) {
		this.signingKeys = signingKeys;
	}

	@GetMapping("/.well-known/jwks.json")
	public ResponseEntity<Map<String, Object>> jwks() {
		return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5))).body(signingKeys.publicJwks());
	}

}
