package com.worfwint.markethub.user.config;

import java.util.List;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("dev")
public class OpenApiConfig {

	@Bean
	OpenAPI serviceOpenApi() {
		return new OpenAPI()
			.info(new Info().title("MarketHub User API").version("v1"))
			// Relative URL keeps Try it out on the gateway's public origin.
			.servers(List.of(new Server().url("/").description("API gateway")))
			.components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
				.type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
	}
}
