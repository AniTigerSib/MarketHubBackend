package com.worfwint.markethub.gateway.ratelimit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Rejects requests with {@code 429 Too Many Requests} once a client IP has used up the
 * allowance of a matching rule. Requests that match no rule pass through untouched.
 *
 * <p>The client is identified by {@link HttpServletRequest#getRemoteAddr()}. Behind a
 * reverse proxy, set {@code server.forward-headers-strategy=native} so that Tomcat takes
 * the address from {@code X-Forwarded-For}; otherwise all clients share the proxy's address.
 */
public class RateLimitFilter extends OncePerRequestFilter {

	private final List<CompiledRule> rules;
	private final RateLimiter limiter;

	public RateLimitFilter(RateLimitProperties properties, RateLimiter limiter) {
		PathPatternParser parser = PathPatternParser.defaultInstance;
		this.rules = properties.rules().stream()
				.map(rule -> new CompiledRule(rule, parser.parse(rule.path())))
				.toList();
		this.limiter = limiter;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		// The servlet path is decoded and normalized by the container ("//auth/login",
		// "/auth/%6Cogin" become "/auth/login"), so such spellings can't slip past a rule.
		String pathInfo = request.getPathInfo();
		PathContainer path = PathContainer.parsePath(request.getServletPath() + (pathInfo == null ? "" : pathInfo));
		String client = request.getRemoteAddr();

		for (CompiledRule compiled : rules) {
			RateLimitProperties.Rule rule = compiled.rule();
			if (!rule.method().isEmpty() && !rule.method().equalsIgnoreCase(request.getMethod())) {
				continue;
			}
			if (!compiled.pattern().matches(path)) {
				continue;
			}
			RateLimiter.Decision decision = limiter.tryConsume(rule.name() + '|' + client, rule.capacity(), rule.window());
			if (!decision.allowed()) {
				reject(response, decision);
				return;
			}
		}
		chain.doFilter(request, response);
	}

	private static void reject(HttpServletResponse response, RateLimiter.Decision decision)
			throws IOException {
		long seconds = Math.max(1, (decision.retryAfter().toMillis() + 999) / 1000);
		response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
		response.setHeader("Retry-After", Long.toString(seconds));
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write("""
				{"status":429,"error":"Too Many Requests","message":"Too many requests. Try again in %d seconds."}"""
				.formatted(seconds));
	}

	private record CompiledRule(RateLimitProperties.Rule rule, PathPattern pattern) {
	}
}
