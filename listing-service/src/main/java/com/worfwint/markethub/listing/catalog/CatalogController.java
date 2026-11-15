package com.worfwint.markethub.listing.catalog;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.worfwint.markethub.listing.catalog.CatalogDtos.Category;
import com.worfwint.markethub.listing.catalog.CatalogDtos.City;

@RestController
public class CatalogController {

	private final CatalogRepository catalog;

	private final CatalogCache cache;

	public CatalogController(CatalogRepository catalog, CatalogCache cache) {
		this.catalog = catalog;
		this.cache = cache;
	}

	@GetMapping("/categories")
	public List<Category> categories() {
		return cache.categories();
	}

	@GetMapping("/cities")
	public List<City> cities() {
		return catalog.findCities();
	}

}
