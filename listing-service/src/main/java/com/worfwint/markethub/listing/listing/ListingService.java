package com.worfwint.markethub.listing.listing;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.worfwint.markethub.listing.catalog.CatalogRepository;
import com.worfwint.markethub.listing.listing.ListingDtos.ListingRequest;
import com.worfwint.markethub.listing.listing.ListingDtos.ListingView;
import com.worfwint.markethub.listing.listing.ListingDtos.MyListings;

@Service
public class ListingService {

	private final ListingRepository listings;

	private final CatalogRepository catalog;

	public ListingService(ListingRepository listings, CatalogRepository catalog) {
		this.listings = listings;
		this.catalog = catalog;
	}

	/** Without moderation yet, a new listing goes live immediately. */
	public ListingView create(UUID sellerId, ListingRequest request) {
		checkCatalogReferences(request);
		long id = listings.insert(sellerId, request);
		return listings.findById(id, sellerId).orElseThrow();
	}

	/** Active listings are public; archived ones are visible only to their seller. */
	public ListingView get(long id, UUID viewerId) {
		return listings.findById(id, viewerId)
			.filter(listing -> "active".equals(listing.status()) || listing.sellerId().equals(viewerId))
			.orElseThrow(ListingService::notFound);
	}

	public ListingView update(long id, UUID sellerId, ListingRequest request) {
		ListingView listing = ownListing(id, sellerId);
		if (!"active".equals(listing.status())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Only active listings can be edited");
		}
		checkCatalogReferences(request);
		listings.update(id, request);
		return listings.findById(id, sellerId).orElseThrow();
	}

	/** "Mark as sold": hides the listing from search. Repeating it changes nothing. */
	public ListingView archive(long id, UUID sellerId) {
		ownListing(id, sellerId);
		listings.updateStatus(id, "archived");
		return listings.findById(id, sellerId).orElseThrow();
	}

	public MyListings mine(UUID sellerId, String status) {
		return new MyListings(listings.findBySeller(sellerId, status), listings.countBySellerPerStatus(sellerId));
	}

	private ListingView ownListing(long id, UUID sellerId) {
		ListingView listing = listings.findById(id, sellerId).orElseThrow(ListingService::notFound);
		if (!listing.sellerId().equals(sellerId)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This is not your listing");
		}
		return listing;
	}

	private void checkCatalogReferences(ListingRequest request) {
		if (!catalog.subcategoryBelongsTo(request.subcategoryId(), request.categoryId())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown category or subcategory");
		}
		if (!catalog.cityExists(request.cityId())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown city");
		}
	}

	private static ResponseStatusException notFound() {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, "Listing not found");
	}

}
