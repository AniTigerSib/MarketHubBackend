package com.worfwint.markethub.gateway.openapi;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.setPath;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.web.servlet.function.RequestPredicates.GET;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

@Configuration
@Profile("dev")
public class OpenApiRoutes {

	@Bean
	RouterFunction<ServerResponse> serviceDocumentRoutes(
			@Value("${AUTH_SERVICE_URL:http://localhost:8082}") String auth,
			@Value("${USER_SERVICE_URL:http://localhost:8081}") String users,
			@Value("${LISTING_SERVICE_URL:http://localhost:8083}") String listings) {
		return documentRoute("auth", auth)
			.and(documentRoute("users", users))
			.and(documentRoute("listings", listings));
	}

	private RouterFunction<ServerResponse> documentRoute(String name, String serviceUrl) {
		return route("openapi-" + name)
			.route(GET("/openapi/" + name), http())
			.before(uri(serviceUrl))
			.before(setPath("/v3/api-docs"))
			.build();
	}
}
