package com.worfwint.markethub.listing.favorite;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.worfwint.markethub.listing.listing.ListingDtos.ListingView;
import com.worfwint.markethub.listing.listing.ListingDtos.Page;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** The caller's saved listings. All endpoints need a token. */
@RestController
@Validated
public class FavoriteController {

	private final FavoriteRepository favorites;

	public FavoriteController(FavoriteRepository favorites) {
		this.favorites = favorites;
	}

	@GetMapping("/favorites")
	public Page<ListingView> list(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "24") @Min(1) @Max(100) int size) {
		return favorites.findByUser(userId(jwt), page, size);
	}

	/** Idempotent; only active listings can be saved. */
	@PutMapping("/favorites/{listingId:\\d+}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void add(@AuthenticationPrincipal Jwt jwt, @PathVariable long listingId) {
		if (!favorites.add(userId(jwt), listingId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Listing not found");
		}
	}

	@DeleteMapping("/favorites/{listingId:\\d+}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable long listingId) {
		favorites.remove(userId(jwt), listingId);
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

}
