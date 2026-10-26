package com.worfwint.markethub.gateway;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Unsigned JWTs for gateway tests; the gateway reads claims but never checks signatures. */
public final class TestTokens {

	private TestTokens() {
	}

	/** {@code Authorization} header value with a token carrying the given claims. */
	public static String bearer(String claimsJson) {
		return "Bearer " + token(claimsJson);
	}

	public static String token(String claimsJson) {
		Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
		return base64.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
				+ base64.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8)) + ".signature";
	}

}
