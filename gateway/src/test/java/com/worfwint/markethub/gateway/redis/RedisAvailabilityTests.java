package com.worfwint.markethub.gateway.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class RedisAvailabilityTests {

	private final AtomicLong now = new AtomicLong(42);

	private final RedisAvailability availability = new RedisAvailability(Duration.ofSeconds(5), now::get);

	@Test
	void skipsRedisForTheCooldownAfterAFailure() {
		assertThat(availability.shouldTry()).isTrue();

		availability.failed(new RuntimeException("down"));
		assertThat(availability.shouldTry()).isFalse();

		now.addAndGet(Duration.ofSeconds(5).toNanos());
		assertThat(availability.shouldTry()).isTrue();

		// Still failing: wait another cooldown
		availability.failed(new RuntimeException("down"));
		assertThat(availability.shouldTry()).isFalse();
	}

	@Test
	void successEndsTheCooldown() {
		availability.failed(new RuntimeException("down"));
		now.addAndGet(Duration.ofSeconds(5).toNanos());
		availability.succeeded();

		assertThat(availability.shouldTry()).isTrue();
	}

}
