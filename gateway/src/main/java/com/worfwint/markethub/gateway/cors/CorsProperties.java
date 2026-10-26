package com.worfwint.markethub.gateway.cors;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which browser origins may call the API through the gateway.
 *
 * @param allowedOrigins exact origins, e.g. {@code http://localhost:5173}
 * @param maxAge how long a browser may cache a preflight answer
 */
@ConfigurationProperties("markethub.cors")
public record CorsProperties(List<String> allowedOrigins, Duration maxAge) {

	public CorsProperties {
		allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
		maxAge = maxAge == null ? Duration.ofHours(1) : maxAge;
	}
}
