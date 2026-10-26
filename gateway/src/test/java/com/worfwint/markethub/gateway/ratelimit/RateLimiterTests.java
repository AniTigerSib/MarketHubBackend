package com.worfwint.markethub.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class RateLimiterTests {

	private final AtomicLong now = new AtomicLong(1_000_000_000L);
	private final RateLimiter limiter = new RateLimiter(now::get);

	@Test
	void allowsCapacityThenRejectsWithRetryAfter() {
		for (int i = 0; i < 3; i++) {
			assertThat(limiter.tryConsume("k", 3, Duration.ofMinutes(1)).allowed()).isTrue();
		}

		RateLimiter.Decision rejected = limiter.tryConsume("k", 3, Duration.ofMinutes(1));

		assertThat(rejected.allowed()).isFalse();
		// One token comes back every 20 seconds (3 per minute).
		assertThat(rejected.retryAfter()).isEqualTo(Duration.ofSeconds(20));
	}

	@Test
	void refillsGraduallyOverTheWindow() {
		for (int i = 0; i < 3; i++) {
			limiter.tryConsume("k", 3, Duration.ofMinutes(1));
		}

		advance(Duration.ofSeconds(19));
		assertThat(limiter.tryConsume("k", 3, Duration.ofMinutes(1)).allowed()).isFalse();

		advance(Duration.ofSeconds(1));
		assertThat(limiter.tryConsume("k", 3, Duration.ofMinutes(1)).allowed()).isTrue();
		assertThat(limiter.tryConsume("k", 3, Duration.ofMinutes(1)).allowed()).isFalse();
	}

	@Test
	void neverRefillsAboveCapacity() {
		advance(Duration.ofHours(5));
		limiter.tryConsume("k", 2, Duration.ofMinutes(1));
		advance(Duration.ofHours(5));

		assertThat(limiter.tryConsume("k", 2, Duration.ofMinutes(1)).allowed()).isTrue();
		assertThat(limiter.tryConsume("k", 2, Duration.ofMinutes(1)).allowed()).isTrue();
		assertThat(limiter.tryConsume("k", 2, Duration.ofMinutes(1)).allowed()).isFalse();
	}

	@Test
	void keysAreIndependent() {
		limiter.tryConsume("a", 1, Duration.ofMinutes(1));

		assertThat(limiter.tryConsume("a", 1, Duration.ofMinutes(1)).allowed()).isFalse();
		assertThat(limiter.tryConsume("b", 1, Duration.ofMinutes(1)).allowed()).isTrue();
	}

	@Test
	void forgetsClientsWhoseAllowanceHasRefilled() {
		limiter.tryConsume("idle", 1, Duration.ofSeconds(10));
		assertThat(limiter.trackedKeys()).isEqualTo(1);

		advance(Duration.ofMinutes(2));
		limiter.tryConsume("active", 1, Duration.ofSeconds(10));

		assertThat(limiter.trackedKeys()).isEqualTo(1);
	}

	private void advance(Duration duration) {
		now.addAndGet(duration.toNanos());
	}
}
