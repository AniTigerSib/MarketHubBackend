package com.worfwint.markethub.listing.listing;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.worfwint.markethub.listing.listing.ListingDtos.ListingRequest;
import com.worfwint.markethub.listing.listing.ListingDtos.ListingView;
import com.worfwint.markethub.listing.listing.ListingDtos.MyListings;
import com.worfwint.markethub.listing.listing.ListingDtos.Page;
import com.worfwint.markethub.listing.listing.ListingDtos.SearchCriteria;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

@RestController
@Validated
public class ListingController {

	private final ListingService listingService;

	private final ListingRepository listings;

	public ListingController(ListingService listingService, ListingRepository listings) {
		this.listingService = listingService;
		this.listings = listings;
	}

	/** Public search. With a token, each listing also says whether the caller saved it. */
	@GetMapping("/listings")
	public Page<ListingView> search(@Valid SearchCriteria criteria, @AuthenticationPrincipal Jwt jwt) {
		return listings.search(criteria, userIdOrNull(jwt));
	}

	@GetMapping("/listings/{id:\\d+}")
	public ListingView get(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
		return listingService.get(id, userIdOrNull(jwt));
	}

	@PostMapping("/listings")
	@ResponseStatus(HttpStatus.CREATED)
	public ListingView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ListingRequest request) {
		return listingService.create(userId(jwt), request);
	}

	@PutMapping("/listings/{id:\\d+}")
	public ListingView update(@PathVariable long id, @AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody ListingRequest request) {
		return listingService.update(id, userId(jwt), request);
	}

	@PostMapping("/listings/{id:\\d+}/archive")
	public ListingView archive(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
		return listingService.archive(id, userId(jwt));
	}

	/** The caller's listings for the "My listings" tabs; optional ?status=active|archived. */
	@GetMapping("/listings/mine")
	public MyListings mine(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(required = false) @Pattern(regexp = "active|archived") String status) {
		return listingService.mine(userId(jwt), status);
	}

	static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

	static UUID userIdOrNull(Jwt jwt) {
		return jwt == null ? null : userId(jwt);
	}

}
