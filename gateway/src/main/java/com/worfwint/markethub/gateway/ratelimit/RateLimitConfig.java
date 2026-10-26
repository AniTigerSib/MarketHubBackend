package com.worfwint.markethub.gateway.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.worfwint.markethub.gateway.cors.CorsConfig;
import com.worfwint.markethub.gateway.redis.RedisAvailability;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
@ConditionalOnBooleanProperty(name = "markethub.rate-limit.enabled", matchIfMissing = true)
public class RateLimitConfig {

	/** Counters in Redis, shared by all gateway instances; in memory while Redis is down. */
	@Bean
	RateLimiter rateLimiter(StringRedisTemplate redis, RedisAvailability availability) {
		return new RedisRateLimiter(redis, availability, new InMemoryRateLimiter());
	}

	@Bean
	FilterRegistrationBean<RateLimitFilter> rateLimitFilter(RateLimitProperties properties, RateLimiter limiter) {
		FilterRegistrationBean<RateLimitFilter> registration =
				new FilterRegistrationBean<>(new RateLimitFilter(properties, limiter));
		// After CORS, so a 429 still carries CORS headers and the browser can read it.
		registration.setOrder(CorsConfig.ORDER + 10);
		return registration;
	}
}
