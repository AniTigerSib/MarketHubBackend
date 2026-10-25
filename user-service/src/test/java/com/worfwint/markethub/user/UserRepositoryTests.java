package com.worfwint.markethub.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Runs the Flyway migrations and native SQL against a real Postgres. Skipped without Docker. */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(UserRepository.class)
@Testcontainers(disabledWithoutDocker = true)
class UserRepositoryTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.1");

	@Autowired
	private UserRepository repository;

	@Test
	void createsProfileFromIdentityAndFindsItCaseInsensitively() {
		UUID id = UUID.randomUUID();
		repository.upsertIdentity(id, "Bob", "bob@markethub.local", "Bob B.");

		assertThat(repository.findByUsername("bob")).hasValueSatisfying(profile -> {
			assertThat(profile.id()).isEqualTo(id);
			assertThat(profile.displayName()).isEqualTo("Bob B.");
		});
	}

	@Test
	void repeatedEventUpdatesIdentityButKeepsEditedProfile() {
		UUID id = UUID.randomUUID();
		repository.upsertIdentity(id, "carol", "carol@markethub.local", "Carol");
		repository.updateProfile(id, "Carol C.", "https://cdn.markethub.local/c.png", "+14155550123");

		repository.upsertIdentity(id, "carol", "carol@new.markethub.local", "Carol");

		assertThat(repository.findById(id)).hasValueSatisfying(profile -> {
			assertThat(profile.email()).isEqualTo("carol@new.markethub.local");
			assertThat(profile.displayName()).isEqualTo("Carol C.");
			assertThat(profile.phone()).isEqualTo("+14155550123");
		});
	}

	@Test
	void updateOfMissingProfileReturnsEmpty() {
		assertThat(repository.updateProfile(UUID.randomUUID(), "X", null, null)).isEmpty();
	}

	@Test
	void databaseRejectsMalformedPhone() {
		UUID id = UUID.randomUUID();
		repository.upsertIdentity(id, "dave", "dave@markethub.local", "Dave");

		assertThatThrownBy(() -> repository.updateProfile(id, "Dave", null, "555-1234"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

}
