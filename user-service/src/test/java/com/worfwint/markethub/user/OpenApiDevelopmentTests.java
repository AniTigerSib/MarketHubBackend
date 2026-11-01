package com.worfwint.markethub.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
	"spring.kafka.listener.auto-startup=false",
	"spring.kafka.admin.auto-create=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class OpenApiDevelopmentTests {

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private UserRepository users;

	@Test
	void documentsPublicAndPrivateProfilesWithoutRequiringAToken() throws Exception {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.servers[0].url").value("/"))
			.andExpect(jsonPath("$.paths['/users/{username}'].get.security").doesNotExist())
			.andExpect(jsonPath("$.paths['/users/me'].get.security[0].bearerAuth").isArray());
	}
}
