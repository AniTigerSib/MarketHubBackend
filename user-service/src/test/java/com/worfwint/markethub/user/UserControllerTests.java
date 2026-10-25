package com.worfwint.markethub.user;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.worfwint.markethub.user.security.SecurityConfig;

@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class UserControllerTests {

	private static final UUID ALICE_ID = UUID.fromString("7f1b6a52-3f0e-4c8e-9d3a-1c2b3d4e5f60");

	private static final UserProfile ALICE = new UserProfile(ALICE_ID, "alice", "alice@markethub.local", "Alice Smith",
			"https://cdn.markethub.local/a.png", "+14155550123");

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private UserRepository userRepository;

	@Test
	void publicProfileNeedsNoTokenAndHidesContactDetails() throws Exception {
		given(userRepository.findByUsername("alice")).willReturn(Optional.of(ALICE));

		mockMvc.perform(get("/users/alice"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("alice"))
			.andExpect(jsonPath("$.displayName").value("Alice Smith"))
			.andExpect(jsonPath("$.email").doesNotExist())
			.andExpect(jsonPath("$.phone").doesNotExist());
	}

	@Test
	void returns404ForUnknownUser() throws Exception {
		given(userRepository.findByUsername("nobody")).willReturn(Optional.empty());

		mockMvc.perform(get("/users/nobody")).andExpect(status().isNotFound());
	}

	@Test
	void ownProfileRequiresToken() throws Exception {
		mockMvc.perform(get("/users/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void returnsOwnFullProfileForTokenSubject() throws Exception {
		given(userRepository.findById(ALICE_ID)).willReturn(Optional.of(ALICE));

		mockMvc.perform(get("/users/me").with(jwt().jwt(token -> token.subject(ALICE_ID.toString()))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value("alice@markethub.local"))
			.andExpect(jsonPath("$.phone").value("+14155550123"));
	}

	@Test
	void updatesOwnProfile() throws Exception {
		UserProfile updated = new UserProfile(ALICE_ID, "alice", "alice@markethub.local", "Alice S.", null,
				"+447700900123");
		given(userRepository.updateProfile(ALICE_ID, "Alice S.", null, "+447700900123")).willReturn(Optional.of(updated));

		mockMvc.perform(put("/users/me").with(jwt().jwt(token -> token.subject(ALICE_ID.toString())))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"displayName\": \"Alice S.\", \"avatarUrl\": null, \"phone\": \"+447700900123\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.displayName").value("Alice S."));
	}

	@Test
	void rejectsInvalidPhoneAndAvatar() throws Exception {
		mockMvc.perform(put("/users/me").with(jwt().jwt(token -> token.subject(ALICE_ID.toString())))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"displayName\": \"Alice\", \"avatarUrl\": \"ftp://x\", \"phone\": \"12345\"}"))
			.andExpect(status().isBadRequest());

		verify(userRepository, never()).updateProfile(any(), any(), any(), any());
	}

	@Test
	void updatingProfileRequiresToken() throws Exception {
		mockMvc.perform(put("/users/me").contentType(MediaType.APPLICATION_JSON).content("{\"displayName\": \"X\"}"))
			.andExpect(status().isUnauthorized());
	}

}
