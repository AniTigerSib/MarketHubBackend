package com.worfwint.markethub.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import com.worfwint.markethub.auth.AuthDtos.RegisterRequest;
import com.worfwint.markethub.auth.AuthDtos.TokenResponse;
import com.worfwint.markethub.auth.account.Account;
import com.worfwint.markethub.auth.revocation.AccessTokenVerifier;

@WebMvcTest(AuthController.class)
class AuthControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AuthService authService;

	@MockitoBean
	private AccessTokenVerifier accessTokens;

	@Test
	void registersAccount() throws Exception {
		UUID id = UUID.randomUUID();
		given(authService.register(any()))
			.willReturn(new Account(id, "alice", "alice@markethub.local", "hash", true, List.of("USER"), 0));

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

	@Test
	void logoutPassesTheVerifiedAccessTokenOn() throws Exception {
		Jwt jwt = Jwt.withTokenValue("access").header("alg", "RS256").subject(UUID.randomUUID().toString())
			.jti("jti-1").expiresAt(Instant.now().plusSeconds(600)).build();
		given(accessTokens.verify("Bearer access")).willReturn(Optional.of(jwt));

		mockMvc.perform(post("/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer access")
				.contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\": \"refresh\"}"))
			.andExpect(status().isNoContent());

		verify(authService).logout("refresh", Optional.of(jwt));
	}

	@Test
	void logoutAllNeedsValidAccessToken() throws Exception {
		given(accessTokens.verify(any())).willReturn(Optional.empty());

		mockMvc.perform(post("/auth/logout-all").header(HttpHeaders.AUTHORIZATION, "Bearer forged"))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/auth/logout-all"))
			.andExpect(status().isUnauthorized());

		verify(authService, never()).logoutAll(any());
	}

}
