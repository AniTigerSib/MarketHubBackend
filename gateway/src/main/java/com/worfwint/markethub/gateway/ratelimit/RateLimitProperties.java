package com.worfwint.markethub.gateway.ratelimit;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-client-IP limits for selected endpoints.
 *
 * @param enabled turns all limits off when false
 * @param rules one limit per endpoint; a request is checked against every matching rule
 */
@ConfigurationProperties("markethub.rate-limit")
public record RateLimitProperties(boolean enabled, List<Rule> rules) {

	public RateLimitProperties {
		rules = rules == null ? List.of() : List.copyOf(rules);
	}

	/**
	 * Allows {@code capacity} requests per {@code window}. Unused allowance builds back up
	 * gradually, so a client that waits a fraction of the window gets that fraction back.
	 *
	 * @param name label used to keep each rule's counters separate
	 * @param method HTTP method to match, or empty for any method
	 * @param path path pattern, e.g. {@code /auth/login}
	 */
	public record Rule(String name, String method, String path, int capacity, Duration window) {

		public Rule {
			if (name == null || name.isBlank()) {
				throw new IllegalArgumentException("rate-limit rule needs a name");
			}
			if (path == null || path.isBlank()) {
				throw new IllegalArgumentException("rate-limit rule '" + name + "' needs a path");
			}
			if (capacity < 1) {
				throw new IllegalArgumentException("rate-limit rule '" + name + "' needs capacity >= 1");
			}
			if (window == null || window.isZero() || window.isNegative()) {
				throw new IllegalArgumentException("rate-limit rule '" + name + "' needs a positive window");
			}
			method = method == null ? "" : method.trim().toUpperCase();
		}
	}
}
