package com.worfwint.markethub.user;

import java.util.UUID;

import org.hibernate.validator.constraints.URL;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request and response bodies of {@link UserController}. */
public final class UserDtos {

	private UserDtos() {
	}

	/** What anyone can see; email and phone stay private. */
	public record PublicProfile(UUID id, String username, String displayName, String avatarUrl) {

		static PublicProfile of(UserProfile profile) {
			return new PublicProfile(profile.id(), profile.username(), profile.displayName(), profile.avatarUrl());
		}

	}

	/** Replaces all editable fields; send null to clear the avatar or phone. */
	public record UpdateProfileRequest(
			@NotBlank @Size(max = 64) String displayName,
			@URL(regexp = "^https?://.*") @Size(max = 2048) String avatarUrl,
			@Pattern(regexp = "^\\+[1-9][0-9]{6,14}$", message = "must be in E.164 format, e.g. +14155550123") String phone) {
	}

}
