package com.worfwint.markethub.gateway.ratelimit;

import java.time.Duration;

/** Token buckets keyed by (rule, client). */
public interface RateLimiter {

	/**
	 * Takes one request from the key's allowance of {@code capacity} requests per
	 * {@code window}. Unused allowance builds back up gradually over the window.
	 *
	 * @return {@link Decision#allowed()} or, when the allowance is used up, how long to wait
	 */
	Decision tryConsume(String key, int capacity, Duration window);

	record Decision(boolean allowed, Duration retryAfter) {

		static final Decision ALLOWED = new Decision(true, Duration.ZERO);

	}

}
