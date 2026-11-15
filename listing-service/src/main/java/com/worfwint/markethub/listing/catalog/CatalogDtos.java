package com.worfwint.markethub.listing.catalog;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** Response bodies of {@link CatalogController}. */
public final class CatalogDtos {

	private CatalogDtos() {
	}

	public record LocalizedName(String en, String ru) {
	}

	/** {@code count} is the active-listing count cached for up to 60 seconds. */
	public record Category(String id, LocalizedName name,
			@Schema(description = "Phosphor CSS classes, including the base ph class", example = "ph ph-car") String icon,
			@Schema(description = "Active listings, cached for up to 60 seconds") long count,
			List<Subcategory> subcategories) {
	}

	public record Subcategory(String id, LocalizedName name,
			@Schema(description = "Active listings, cached for up to 60 seconds") long count) {
	}

	public record City(String id, LocalizedName name) {
	}

}
