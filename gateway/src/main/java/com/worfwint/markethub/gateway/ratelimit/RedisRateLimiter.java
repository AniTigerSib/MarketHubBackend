package com.worfwint.markethub.gateway.ratelimit;

import java.time.Duration;
import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.worfwint.markethub.gateway.redis.RedisAvailability;

/**
 * Token buckets stored in Redis ({@code rl:<rule>:<client>}), so counters survive restarts and
 * are shared by every gateway instance. Falls back to in-memory buckets while Redis is down.
 */
public class RedisRateLimiter implements RateLimiter {

	static final String KEY_PREFIX = "rl:";

	/**
	 * Same algorithm as {@link InMemoryRateLimiter}, run atomically in Redis using Redis's own
	 * clock, so gateway instances don't need synchronized clocks. A bucket expires after one
	 * window, when it would be full again anyway.
	 * Returns {allowed (1/0), milliseconds to wait}.
	 */
	@SuppressWarnings("rawtypes")
	private static final RedisScript<List> TOKEN_BUCKET = RedisScript.of("""
			local capacity = tonumber(ARGV[1])
			local window_ms = tonumber(ARGV[2])
			local time = redis.call('TIME')
			local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
			local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
			local tokens = tonumber(state[1]) or capacity
			local ts = tonumber(state[2]) or now
			if now > ts then
				tokens = math.min(capacity, tokens + (now - ts) * capacity / window_ms)
			end
			local allowed = 0
			local wait = 0
			if tokens >= 1 then
				tokens = tokens - 1
				allowed = 1
			else
				wait = math.ceil((1 - tokens) * window_ms / capacity)
			end
			redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(math.max(now, ts)))
			redis.call('PEXPIRE', KEYS[1], window_ms)
			return {allowed, wait}
			""", List.class);

	private final StringRedisTemplate redis;

	private final RedisAvailability availability;

	private final InMemoryRateLimiter fallback;

	public RedisRateLimiter(StringRedisTemplate redis, RedisAvailability availability, InMemoryRateLimiter fallback) {
		this.redis = redis;
		this.availability = availability;
		this.fallback = fallback;
	}

	@Override
	public Decision tryConsume(String key, int capacity, Duration window) {
		if (availability.shouldTry()) {
			try {
				List<?> result = redis.execute(TOKEN_BUCKET, List.of(KEY_PREFIX + key), Integer.toString(capacity),
						Long.toString(window.toMillis()));
				availability.succeeded();
				if (((Number) result.get(0)).longValue() == 1) {
					return Decision.ALLOWED;
				}
				return new Decision(false, Duration.ofMillis(((Number) result.get(1)).longValue()));
			}
			catch (DataAccessException ex) {
				availability.failed(ex);
			}
		}
		return fallback.tryConsume(key, capacity, window);
	}

}
