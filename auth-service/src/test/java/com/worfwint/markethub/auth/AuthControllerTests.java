package com.worfwint.markethub.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import com.worfwint.markethub.auth.AuthDtos.RegisterRequest;
import com.worfwint.markethub.auth.AuthDtos.TokenResponse;
import com.worfwint.markethub.auth.account.Account;

@WebMvcTest(AuthController.class)
class AuthControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AuthService authService;

	@Test
	void registersAccount() throws Exception {
		UUID id = UUID.randomUUID();
		given(authService.register(any()))
			.willReturn(new Account(id, "alice", "alice@markethub.local", "hash", true, List.of("USER")));

		mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"username": "alice", "email": "alice@markethub.local", "password": "s3cret-pass", "displayName": "Alice"}
						"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.userId").value(id.toString()))
			.andExpect(jsonPath("$.username").value("alice"))
			.andExpect(jsonPath("$.passwordHash").doesNotExist());

		verify(authService).register(new RegisterRequest("alice", "alice@markethub.local", "s3cret-pass", "Alice"));
	}

	@Test
	void rejectsInvalidRegistration() throws Exception {
		mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"username": "a@b", "email": "not-an-email", "password": "short"}
						"""))
			.andExpect(status().isBadRequest());

		verify(authService, never()).register(any());
	}

	@Test
	void logsIn() throws Exception {
		given(authService.login("alice@markethub.local", "s3cret-pass"))
			.willReturn(new TokenResponse("access", "refresh", "Bearer", 900));

		mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"login\": \"alice@markethub.local\", \"password\": \"s3cret-pass\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").value("access"))
			.andExpect(jsonPath("$.refreshToken").value("refresh"))
			.andExpect(jsonPath("$.tokenType").value("Bearer"))
			.andExpect(jsonPath("$.expiresIn").value(900));
	}

	@Test
	void returns401ForBadCredentials() throws Exception {
		given(authService.login(any(), any()))
			.willThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid login or password"));

		mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"login\": \"alice\", \"password\": \"wrong-pass\"}"))
			.andExpect(status().isUnauthorized());
	}

}
