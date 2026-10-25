package com.worfwint.markethub.auth.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

	// Roles are folded into one array column so an account loads in a single query
	private static final String SELECT_ACCOUNT = """
			SELECT a.id, a.username, a.email, a.password_hash, a.enabled,
			       COALESCE(array_agg(r.role ORDER BY r.role) FILTER (WHERE r.role IS NOT NULL), '{}') AS roles
			FROM accounts a
			LEFT JOIN account_roles r ON r.account_id = a.id
			""";

	private final JdbcClient jdbc;

	public AccountRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Finds an account by email if {@code login} contains '@', otherwise by username. Case-insensitive. */
	public Optional<Account> findByLogin(String login) {
		String column = login.contains("@") ? "a.email" : "a.username";
		return jdbc.sql(SELECT_ACCOUNT + " WHERE lower(" + column + ") = lower(:login) GROUP BY a.id")
			.param("login", login)
			.query(AccountRepository::mapAccount)
			.optional();
	}

	public Optional<Account> findById(UUID id) {
		return jdbc.sql(SELECT_ACCOUNT + " WHERE a.id = :id GROUP BY a.id")
			.param("id", id)
			.query(AccountRepository::mapAccount)
			.optional();
	}

	public boolean existsByUsername(String username) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM accounts WHERE lower(username) = lower(:username))")
			.param("username", username)
			.query(Boolean.class)
			.single();
	}

	public boolean existsByEmail(String email) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM accounts WHERE lower(email) = lower(:email))")
			.param("email", email)
			.query(Boolean.class)
			.single();
	}

	/** Throws {@link org.springframework.dao.DuplicateKeyException} if the username or email is taken. */
	public void insert(UUID id, String username, String email, String passwordHash) {
		jdbc.sql("""
				INSERT INTO accounts (id, username, email, password_hash)
				VALUES (:id, :username, :email, :passwordHash)
				""")
			.param("id", id)
			.param("username", username)
			.param("email", email)
			.param("passwordHash", passwordHash)
			.update();
	}

	public void addRole(UUID accountId, String role) {
		jdbc.sql("INSERT INTO account_roles (account_id, role) VALUES (:accountId, :role) ON CONFLICT DO NOTHING")
			.param("accountId", accountId)
			.param("role", role)
			.update();
	}

	public void updateLastLogin(UUID id, Instant loggedInAt) {
		jdbc.sql("UPDATE accounts SET last_login_at = :loggedInAt WHERE id = :id")
			.param("id", id)
			.param("loggedInAt", OffsetDateTime.ofInstant(loggedInAt, ZoneOffset.UTC))
			.update();
	}

	private static Account mapAccount(ResultSet rs, int rowNum) throws SQLException {
		String[] roles = (String[]) rs.getArray("roles").getArray();
		return new Account(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("email"),
				rs.getString("password_hash"), rs.getBoolean("enabled"), List.of(roles));
	}

}
