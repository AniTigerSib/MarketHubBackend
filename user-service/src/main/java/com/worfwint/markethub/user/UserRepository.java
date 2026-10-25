package com.worfwint.markethub.user;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

	private static final String COLUMNS = "id, username, email, display_name, avatar_url, phone";

	private final JdbcClient jdbc;

	public UserRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<UserProfile> findById(UUID id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE id = :id")
			.param("id", id)
			.query(UserRepository::mapProfile)
			.optional();
	}

	public Optional<UserProfile> findByUsername(String username) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE lower(username) = lower(:username)")
			.param("username", username)
			.query(UserRepository::mapProfile)
			.optional();
	}

	/**
	 * Creates the profile, or refreshes its identity fields if it exists. Safe to repeat,
	 * since Kafka may deliver the same event more than once. Profile fields the user
	 * edited here are left alone.
	 */
	public void upsertIdentity(UUID id, String username, String email, String displayName) {
		jdbc.sql("""
				INSERT INTO users (id, username, email, display_name)
				VALUES (:id, :username, :email, :displayName)
				ON CONFLICT (id) DO UPDATE
				SET username = EXCLUDED.username,
				    email = EXCLUDED.email,
				    updated_at = now()
				WHERE users.username IS DISTINCT FROM EXCLUDED.username
				   OR users.email IS DISTINCT FROM EXCLUDED.email
				""")
			.param("id", id)
			.param("username", username)
			.param("email", email)
			.param("displayName", displayName)
			.update();
	}

	public Optional<UserProfile> updateProfile(UUID id, String displayName, String avatarUrl, String phone) {
		return jdbc.sql("""
				UPDATE users
				SET display_name = :displayName, avatar_url = :avatarUrl, phone = :phone, updated_at = now()
				WHERE id = :id
				RETURNING\s""" + COLUMNS)
			.param("id", id)
			.param("displayName", displayName)
			.param("avatarUrl", avatarUrl)
			.param("phone", phone)
			.query(UserRepository::mapProfile)
			.optional();
	}

	private static UserProfile mapProfile(ResultSet rs, int rowNum) throws SQLException {
		return new UserProfile(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("email"),
				rs.getString("display_name"), rs.getString("avatar_url"), rs.getString("phone"));
	}

}
