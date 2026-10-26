package com.worfwint.markethub.gateway.redis;

import java.time.Duration;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.worfwint.markethub.gateway.cors.CorsConfig;
import com.worfwint.markethub.gateway.revocation.TokenBlacklistFilter;

@Configuration
public class RedisConfig {

	/** How long to skip Redis after it failed, before one request tries again. */
	private static final Duration RETRY_AFTER_FAILURE = Duration.ofSeconds(5);

	@Bean
	RedisAvailability redisAvailability() {
		return new RedisAvailability(RETRY_AFTER_FAILURE);
	}

	@Bean
	FilterRegistrationBean<TokenBlacklistFilter> tokenBlacklistFilter(StringRedisTemplate redis,
			RedisAvailability availability) {
		FilterRegistrationBean<TokenBlacklistFilter> registration =
				new FilterRegistrationBean<>(new TokenBlacklistFilter(redis, availability));
		// After CORS (so a 401 is readable by the browser) and after rate limiting (so a flood
		// of requests is cut off before it costs Redis lookups)
		registration.setOrder(CorsConfig.ORDER + 20);
		return registration;
	}

}
