package com.worfwint.markethub.gateway.redis;

import java.time.Duration;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Remembers that Redis just failed, so requests skip it for a short while instead of each
 * waiting for the timeout. Logs only when Redis goes down and when it comes back.
 */
public class RedisAvailability {

	private static final Logger log = LoggerFactory.getLogger(RedisAvailability.class);

	private final long cooldownNanos;

	private final LongSupplier nanoClock;

	private volatile boolean down;

	private volatile long retryAt;

	public RedisAvailability(Duration cooldown) {
		this(cooldown, System::nanoTime);
	}

	RedisAvailability(Duration cooldown, LongSupplier nanoClock) {
		this.cooldownNanos = cooldown.toNanos();
		this.nanoClock = nanoClock;
	}

	/** False for a short while after a failure; then one request gets to try again. */
	public boolean shouldTry() {
		return !down || nanoClock.getAsLong() - retryAt >= 0;
	}

	public void succeeded() {
		if (down) {
			down = false;
			log.info("Redis is reachable again; token blacklist and shared rate limits are back on");
		}
	}

	public void failed(Exception ex) {
		retryAt = nanoClock.getAsLong() + cooldownNanos;
		if (!down) {
			down = true;
			log.warn("Redis is unavailable; letting tokens through unchecked and counting rate limits in memory: {}",
					ex.toString());
		}
	}

}
