package com.worfwint.markethub.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.worfwint.markethub.listing.catalog.CatalogCache;
import com.worfwint.markethub.listing.catalog.CatalogDtos.Category;
import com.worfwint.markethub.listing.catalog.CatalogDtos.LocalizedName;
import com.worfwint.markethub.listing.catalog.CatalogDtos.Subcategory;
import com.worfwint.markethub.listing.catalog.CatalogRepository;

class CatalogCacheTests {

	private static final String KEY = "catalog:categories:v1";
	private final CatalogRepository repository = mock(CatalogRepository.class);
	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> values = mock(ValueOperations.class);
	private final CatalogCache cache = new CatalogCache(repository, redis);
	private final List<Category> categories = List.of(new Category("transport",
		new LocalizedName("Transport", "Транспорт"), "ph ph-car", 2,
		List.of(new Subcategory("cars", new LocalizedName("Cars", "Автомобили"), 2))));

	@Test
	void missPopulatesCacheAndHitAvoidsDatabaseAndDoesNotExtendTtl() {
		when(redis.opsForValue()).thenReturn(values);
		when(repository.findCategories()).thenReturn(categories);
		assertThat(cache.categories()).isEqualTo(categories);
		var json = org.mockito.ArgumentCaptor.forClass(String.class);
		verify(values).set(eq(KEY), json.capture(), eq(Duration.ofSeconds(60)));
		when(values.get(KEY)).thenReturn(json.getValue());
		assertThat(cache.categories()).isEqualTo(categories);
		verify(repository, times(1)).findCategories();
		verify(values, times(1)).set(eq(KEY), anyString(), any(Duration.class));
	}

	@Test
	void redisFailureStillReturnsDatabaseResult() {
		when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("unavailable"));
		when(repository.findCategories()).thenReturn(categories);
		assertThat(cache.categories()).isEqualTo(categories);
	}

	@Test
	void malformedCacheIsReplacedWithFreshData() {
		when(redis.opsForValue()).thenReturn(values);
		when(values.get(KEY)).thenReturn("broken json");
		when(repository.findCategories()).thenReturn(categories);
		assertThat(cache.categories()).isEqualTo(categories);
		verify(values).set(eq(KEY), anyString(), eq(Duration.ofSeconds(60)));
	}
}
