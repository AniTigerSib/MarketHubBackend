package com.worfwint.markethub.gateway.ratelimit;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * In-memory token buckets, one per (rule, client) key. Each gateway instance counts on its
 * own, so with several instances a client gets the limit once per instance. Used when Redis
 * is unavailable.
 */
public class InMemoryRateLimiter implements RateLimiter {

	private static final long SWEEP_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

	private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
	private final LongSupplier nanoClock;
	private final AtomicLong nextSweep;

	public InMemoryRateLimiter() {
		this(System::nanoTime);
	}

	InMemoryRateLimiter(LongSupplier nanoClock) {
		this.nanoClock = nanoClock;
		this.nextSweep = new AtomicLong(nanoClock.getAsLong() + SWEEP_INTERVAL_NANOS);
	}

	@Override
	public Decision tryConsume(String key, int capacity, Duration window) {
		long now = nanoClock.getAsLong();
		sweepIfDue(now);
		Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, window, now));
		return bucket.tryConsume(now);
	}

	int trackedKeys() {
		return buckets.size();
	}

	/** Drops buckets that have refilled completely; they behave the same as a new bucket. */
	private void sweepIfDue(long now) {
		long due = nextSweep.get();
		if (now - due < 0 || !nextSweep.compareAndSet(due, now + SWEEP_INTERVAL_NANOS)) {
			return;
		}
		buckets.values().removeIf(bucket -> bucket.isFull(now));
	}

	private static final class Bucket {

		private final double capacity;
		private final double tokensPerNano;
		private double tokens;
		private long lastRefill;

		Bucket(int capacity, Duration window, long now) {
			this.capacity = capacity;
			this.tokensPerNano = capacity / (double) window.toNanos();
			this.tokens = capacity;
			this.lastRefill = now;
		}

		synchronized Decision tryConsume(long now) {
			refill(now);
			if (tokens >= 1) {
				tokens -= 1;
				return Decision.ALLOWED;
			}
			long waitNanos = (long) Math.ceil((1 - tokens) / tokensPerNano);
			return new Decision(false, Duration.ofNanos(waitNanos));
		}

		synchronized boolean isFull(long now) {
			refill(now);
			return tokens >= capacity;
		}

		private void refill(long now) {
			long elapsed = now - lastRefill;
			if (elapsed > 0) {
				tokens = Math.min(capacity, tokens + elapsed * tokensPerNano);
				lastRefill = now;
			}
		}
	}
}
