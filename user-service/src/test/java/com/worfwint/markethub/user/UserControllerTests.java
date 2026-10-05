package com.worfwint.markethub.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(UserController.class)
class UserControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void returnsKnownUser() throws Exception {
		mockMvc.perform(get("/users/alice"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("alice"))
			.andExpect(jsonPath("$.fullName").value("Alice Smith"));
	}

	@Test
	void returns404ForUnknownUser() throws Exception {
		mockMvc.perform(get("/users/nobody"))
			.andExpect(status().isNotFound());
	}

}
