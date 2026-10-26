package com.worfwint.markethub.gateway.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.worfwint.markethub.gateway.cors.CorsConfig;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
@ConditionalOnBooleanProperty(name = "markethub.rate-limit.enabled", matchIfMissing = true)
public class RateLimitConfig {

	@Bean
	RateLimiter rateLimiter() {
		return new RateLimiter();
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
