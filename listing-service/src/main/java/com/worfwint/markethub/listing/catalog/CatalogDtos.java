package com.worfwint.markethub.listing.catalog;

import java.util.List;

/** Response bodies of {@link CatalogController}. */
public final class CatalogDtos {

	private CatalogDtos() {
	}

	public record LocalizedName(String en, String ru) {
	}

	/** {@code count} is the number of active listings. */
	public record Category(String id, LocalizedName name, long count, List<Subcategory> subcategories) {
	}

	public record Subcategory(String id, LocalizedName name, long count) {
	}

	public record City(String id, LocalizedName name) {
	}

}
