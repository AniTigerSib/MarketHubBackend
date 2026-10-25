package com.worfwint.markethub.listing.listing;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.validator.constraints.URL;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Request and response bodies of {@link ListingController}. */
public final class ListingDtos {

	private ListingDtos() {
	}

	/** Body of create and update. Category, subcategory and city ids come from GET /categories and /cities. */
	public record ListingRequest(
			@NotBlank String categoryId,
			@NotBlank String subcategoryId,
			@NotBlank String cityId,
			@NotBlank @Size(min = 5, max = 100) String title,
			@NotBlank @Size(min = 30, max = 4000) String description,
			@NotNull @PositiveOrZero @Max(1_000_000_000_000L) Long price,
			/** Null for a one-off price; "month" (e.g. rent, salary) or "sqm" (per square metre). */
			@Pattern(regexp = "month|sqm") String priceUnit,
			@NotNull @Pattern(regexp = "new|used") String condition,
			@NotNull @Pattern(regexp = "private|business") String sellerType,
			/** Up to 10 photo URLs; the first is the cover. */
			@Size(max = 10) List<@NotBlank @Size(max = 2048) @URL(regexp = "^https?://.*") String> photoUrls,
			boolean contactByMessages,
			boolean contactByPhone) {

		public ListingRequest {
			photoUrls = photoUrls == null ? List.of() : List.copyOf(photoUrls);
		}

		@AssertTrue(message = "choose at least one way buyers can contact you")
		public boolean isContactMethodChosen() {
			return contactByMessages || contactByPhone;
		}

	}

	/**
	 * @param favoritesCount how many users saved this listing
	 * @param favorite whether the caller saved it; always false without a token
	 */
	public record ListingView(long id, UUID sellerId, String categoryId, String subcategoryId, String cityId,
			String title, String description, long price, String priceUnit, String condition, String sellerType,
			List<String> photoUrls, boolean contactByMessages, boolean contactByPhone, String status,
			long favoritesCount, boolean favorite, Instant createdAt, Instant updatedAt) {
	}

	/** Query parameters of GET /listings. Every filter is optional. */
	public record SearchCriteria(
			@Size(max = 200) String q,
			String category,
			String subcategory,
			String city,
			@PositiveOrZero Long minPrice,
			@PositiveOrZero Long maxPrice,
			/** Repeatable: ?condition=new&condition=used */
			List<@Pattern(regexp = "new|used") String> condition,
			@Pattern(regexp = "private|business") String sellerType,
			Boolean withPhoto,
			/** "new" (default), "cheap" or "expensive". */
			@Pattern(regexp = "new|cheap|expensive") String sort,
			@Min(0) Integer page,
			@Min(1) @Max(100) Integer size) {

		public SearchCriteria {
			condition = condition == null ? List.of() : List.copyOf(condition);
			sort = sort == null ? "new" : sort;
			page = page == null ? 0 : page;
			size = size == null ? 24 : size;
		}

	}

	public record Page<T>(List<T> items, long total, int page, int size) {
	}

	/** {@code counts} has the number of the caller's listings per status, for the account tabs. */
	public record MyListings(List<ListingView> items, Map<String, Long> counts) {
	}

}
