package com.worfwint.markethub.listing.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.worfwint.markethub.listing.catalog.CatalogDtos.Category;
import com.worfwint.markethub.listing.catalog.CatalogDtos.City;
import com.worfwint.markethub.listing.catalog.CatalogDtos.LocalizedName;
import com.worfwint.markethub.listing.catalog.CatalogDtos.Subcategory;

@Repository
public class CatalogRepository {

	private final JdbcClient jdbc;

	public CatalogRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** All categories with their subcategories, in display order, with active-listing counts. */
	public List<Category> findCategories() {
		// One row per subcategory, with its own count and its category's count
		record Row(String categoryId, String categoryEn, String categoryRu, long categoryCount, String subId,
				String subEn, String subRu, long subCount) {
		}
		List<Row> rows = jdbc.sql("""
				WITH counts AS (
				    SELECT subcategory_id, count(*) AS n
				    FROM listings
				    WHERE status = 'active'
				    GROUP BY subcategory_id
				)
				SELECT c.id AS category_id, c.name_en AS category_en, c.name_ru AS category_ru,
				       COALESCE(sum(COALESCE(n.n, 0)) OVER (PARTITION BY c.id), 0) AS category_count,
				       s.id AS sub_id, s.name_en AS sub_en, s.name_ru AS sub_ru, COALESCE(n.n, 0) AS sub_count
				FROM categories c
				JOIN subcategories s ON s.category_id = c.id
				LEFT JOIN counts n ON n.subcategory_id = s.id
				ORDER BY c.position, s.position
				""")
			.query((rs, rowNum) -> new Row(rs.getString("category_id"), rs.getString("category_en"),
					rs.getString("category_ru"), rs.getLong("category_count"), rs.getString("sub_id"),
					rs.getString("sub_en"), rs.getString("sub_ru"), rs.getLong("sub_count")))
			.list();

		Map<String, Category> categories = new LinkedHashMap<>();
		for (Row row : rows) {
			Category category = categories.computeIfAbsent(row.categoryId(), id -> new Category(id,
					new LocalizedName(row.categoryEn(), row.categoryRu()), row.categoryCount(), new ArrayList<>()));
			category.subcategories().add(new Subcategory(row.subId(), new LocalizedName(row.subEn(), row.subRu()),
					row.subCount()));
		}
		return List.copyOf(categories.values());
	}

	public List<City> findCities() {
		return jdbc.sql("SELECT id, name_en, name_ru FROM cities ORDER BY position")
			.query((rs, rowNum) -> new City(rs.getString("id"),
					new LocalizedName(rs.getString("name_en"), rs.getString("name_ru"))))
			.list();
	}

	public boolean subcategoryBelongsTo(String subcategoryId, String categoryId) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM subcategories WHERE id = :sub AND category_id = :cat)")
			.param("sub", subcategoryId)
			.param("cat", categoryId)
			.query(Boolean.class)
			.single();
	}

	public boolean cityExists(String cityId) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM cities WHERE id = :id)")
			.param("id", cityId)
			.query(Boolean.class)
			.single();
	}

}
