package com.worfwint.markethub.listing.catalog;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import com.worfwint.markethub.listing.catalog.CatalogDtos.Category;

/** Lazy shared cache: Redis expires the snapshot 60 seconds after it is written. */
@Service
public class CatalogCache {

	static final String KEY = "catalog:categories:v1";
	private static final Duration TTL = Duration.ofSeconds(60);
	private static final Logger log = LoggerFactory.getLogger(CatalogCache.class);
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final TypeReference<List<Category>> CATEGORIES = new TypeReference<>() { };

	private final CatalogRepository catalog;
	private final StringRedisTemplate redis;

	public CatalogCache(CatalogRepository catalog, StringRedisTemplate redis) {
		this.catalog = catalog;
		this.redis = redis;
	}

	// Coalesce requests within one instance on a cache miss.
	public synchronized List<Category> categories() {
		try {
			String cached = redis.opsForValue().get(KEY);
			if (cached != null) {
				List<Category> categories = JSON.readValue(cached, CATEGORIES);
				if (categories != null) {
					return categories;
				}
			}
		}
		catch (DataAccessException | JacksonException ex) {
			log.warn("Category cache read failed; loading from PostgreSQL", ex);
		}

		List<Category> categories = catalog.findCategories();
		try {
			redis.opsForValue().set(KEY, JSON.writeValueAsString(categories), TTL);
		}
		catch (DataAccessException | JacksonException ex) {
			log.warn("Category cache write failed; returning PostgreSQL result", ex);
		}
		return categories;
	}
}
