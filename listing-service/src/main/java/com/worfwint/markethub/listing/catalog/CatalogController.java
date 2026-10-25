package com.worfwint.markethub.listing.catalog;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.worfwint.markethub.listing.catalog.CatalogDtos.Category;
import com.worfwint.markethub.listing.catalog.CatalogDtos.City;

@RestController
public class CatalogController {

	private final CatalogRepository catalog;

	public CatalogController(CatalogRepository catalog) {
		this.catalog = catalog;
	}

	@GetMapping("/categories")
	public List<Category> categories() {
		return catalog.findCategories();
	}

	@GetMapping("/cities")
	public List<City> cities() {
		return catalog.findCities();
	}

}
