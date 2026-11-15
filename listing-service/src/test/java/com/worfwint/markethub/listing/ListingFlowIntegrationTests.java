package com.worfwint.markethub.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.jayway.jsonpath.JsonPath;

/**
 * Full HTTP flow against a real Postgres with Flyway migrations. Skipped without Docker.
 * Tests share one database, so each one tags its listings with a unique word and
 * searches by it.
 */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("dev")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ListingFlowIntegrationTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.1");

	@Container
	@ServiceConnection(name = "redis")
	static GenericContainer<?> redis = new GenericContainer<>("redis:8.2").withExposedPorts(6379);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private StringRedisTemplate redisTemplate;

	private final UUID seller = UUID.randomUUID();

	private final UUID buyer = UUID.randomUUID();

	@Test
	void developmentDocumentIsPublicAndDescribesAuthentication() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.servers[0].url").value("/"))
			.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
			.andExpect(jsonPath("$.components.schemas.Category.properties.icon.example").value("ph ph-car"))
			.andExpect(jsonPath("$.paths['/listings'].get.security").doesNotExist())
			.andExpect(jsonPath("$.paths['/listings'].post.security[0].bearerAuth").isArray())
			.andExpect(jsonPath("$.paths['/listings'].get.parameters[?(@.name == 'q')]").isNotEmpty())
			.andExpect(jsonPath("$.paths['/favorites'].get.security[0].bearerAuth").isArray());
	}

	@Test
	void sellerCreatesEditsAndArchivesListing() throws Exception {
		String tag = tag();
		long id = create(seller, body(tag + " iPhone 13 blue", "electronics", "phones", "msk", 41990, "used", "private", true));

		mockMvc.perform(get("/listings/" + id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("active"))
			.andExpect(jsonPath("$.sellerId").value(seller.toString()))
			.andExpect(jsonPath("$.photoUrls[0]").value("https://cdn.markethub.local/cover.jpg"));

		String edited = body(tag + " iPhone 13 blue, price drop", "electronics", "phones", "msk", 39990, "used",
				"private", true);
		mockMvc.perform(put("/listings/" + id).with(as(buyer)).contentType(MediaType.APPLICATION_JSON).content(edited))
			.andExpect(status().isForbidden());
		mockMvc.perform(put("/listings/" + id).with(as(seller)).contentType(MediaType.APPLICATION_JSON).content(edited))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.price").value(39990));

		mockMvc.perform(post("/listings/" + id + "/archive").with(as(seller)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("archived"));

		// Archived: gone from search and hidden from others, still visible to the seller
		assertThat(searchIds("q", tag)).isEmpty();
		mockMvc.perform(get("/listings/" + id)).andExpect(status().isNotFound());
		mockMvc.perform(get("/listings/" + id).with(as(seller))).andExpect(status().isOk());
		mockMvc.perform(put("/listings/" + id).with(as(seller)).contentType(MediaType.APPLICATION_JSON).content(edited))
			.andExpect(status().isConflict());

		mockMvc.perform(get("/listings/mine").with(as(seller)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.counts.active").value(0))
			.andExpect(jsonPath("$.counts.archived").value(1))
			.andExpect(jsonPath("$.items[0].id").value(id));
	}

	@Test
	void rejectsUnknownOrMismatchedCatalogIds() throws Exception {
		mockMvc.perform(post("/listings").with(as(seller)).contentType(MediaType.APPLICATION_JSON)
				.content(body(tag() + " sofa", "electronics", "furniture", "msk", 100, "used", "private", false)))
			.andExpect(status().isBadRequest());
		mockMvc.perform(post("/listings").with(as(seller)).contentType(MediaType.APPLICATION_JSON)
				.content(body(tag() + " sofa", "home", "furniture", "paris", 100, "used", "private", false)))
			.andExpect(status().isBadRequest());
	}

	@Test
	void searchFiltersSortsAndPaginates() throws Exception {
		String tag = tag();
		long phone = create(seller, body(tag + " iPhone 13", "electronics", "phones", "msk", 41990, "used", "private", true));
		long laptop = create(seller, body(tag + " MacBook Air M1", "electronics", "laptops", "nsk", 54000, "new", "business", false));
		long bike = create(seller, body(tag + " Горный велосипед Trek", "hobbies", "bikes", "msk", 38000, "used", "private", true));

		// Partial words, in either language
		assertThat(searchIds("q", tag + " iphon")).containsExactly(phone);
		assertThat(searchIds("q", tag + " велосип")).containsExactly(bike);

		assertThat(searchIds("q", tag, "category", "electronics")).containsExactlyInAnyOrder(phone, laptop);
		assertThat(searchIds("q", tag, "subcategory", "laptops")).containsExactly(laptop);
		assertThat(searchIds("q", tag, "city", "msk")).containsExactlyInAnyOrder(phone, bike);
		assertThat(searchIds("q", tag, "minPrice", "40000", "maxPrice", "50000")).containsExactly(phone);
		assertThat(searchIds("q", tag, "condition", "new")).containsExactly(laptop);
		assertThat(searchIds("q", tag, "sellerType", "private")).containsExactlyInAnyOrder(phone, bike);
		assertThat(searchIds("q", tag, "withPhoto", "true")).containsExactlyInAnyOrder(phone, bike);

		assertThat(searchIds("q", tag, "sort", "cheap")).containsExactly(bike, phone, laptop);
		assertThat(searchIds("q", tag, "sort", "expensive")).containsExactly(laptop, phone, bike);
		assertThat(searchIds("q", tag)).containsExactly(bike, laptop, phone); // newest first

		mockMvc.perform(get("/listings").param("q", tag).param("size", "2").param("page", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(3))
			.andExpect(jsonPath("$.items.length()").value(1));
	}

	@Test
	void categoriesExposePhosphorIcons() throws Exception {
		String body = mockMvc.perform(get("/categories"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		List<Map<String, Object>> categories = JsonPath.read(body, "$");
		Map<String, String> expected = Map.of(
			"transport", "ph ph-car", "realty", "ph ph-buildings",
			"jobs", "ph ph-briefcase", "services", "ph ph-wrench",
			"electronics", "ph ph-device-mobile", "home", "ph ph-armchair",
			"clothing", "ph ph-t-shirt", "kids", "ph ph-baby",
			"hobbies", "ph ph-bicycle", "pets", "ph ph-paw-print");
		assertThat(categories).hasSize(expected.size());
		for (Map<String, Object> category : categories) {
			assertThat(category.get("icon")).isEqualTo(expected.get(category.get("id")));
		}
	}

	@Test
	void categoriesCacheCountsUntilRedisEntryExpires() throws Exception {
		redisTemplate.expire("catalog:categories:v1", java.time.Duration.ZERO);
		String before = mockMvc.perform(get("/categories")).andReturn().getResponse().getContentAsString();
		List<Number> petsCounts = JsonPath.read(before, "$[?(@.id == 'pets')].count");
		int petsBefore = petsCounts.getFirst().intValue();
		assertThat(redisTemplate.getExpire("catalog:categories:v1")).isBetween(1L, 60L);
		List<Number> suppliesCounts = JsonPath.read(before,
			"$[?(@.id == 'pets')].subcategories[?(@.id == 'supplies')].count");
		int suppliesBefore = suppliesCounts.getFirst().intValue();

		long listingId = create(seller, body(tag() + " Cat tree 150 cm", "pets", "supplies", "spb", 4800, "new", "business", false));

		// Cache hits keep the prior counts and do not extend the 60-second lifetime.
		mockMvc.perform(get("/categories"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == 'pets')].count").value(petsBefore));
		redisTemplate.expire("catalog:categories:v1", java.time.Duration.ZERO);

		mockMvc.perform(get("/categories"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(10))
			.andExpect(jsonPath("$[0].id").value("transport"))
			.andExpect(jsonPath("$[0].name.ru").value("Транспорт"))
			.andExpect(jsonPath("$[?(@.id == 'pets')].count").value(petsBefore + 1))
			.andExpect(jsonPath("$[?(@.id == 'pets')].subcategories[?(@.id == 'supplies')].count").value(suppliesBefore + 1));
		mockMvc.perform(post("/listings/" + listingId + "/archive").with(as(seller)))
			.andExpect(status().isOk());
		mockMvc.perform(get("/categories"))
			.andExpect(jsonPath("$[?(@.id == 'pets')].count").value(petsBefore + 1));
		redisTemplate.expire("catalog:categories:v1", java.time.Duration.ZERO);
		mockMvc.perform(get("/categories"))
			.andExpect(jsonPath("$[?(@.id == 'pets')].count").value(petsBefore))
			.andExpect(jsonPath("$[?(@.id == 'pets')].subcategories[?(@.id == 'supplies')].count").value(suppliesBefore));
		mockMvc.perform(get("/cities"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].name.en").value("Moscow"));
	}

	@Test
	void buyerSavesAndRemovesFavorites() throws Exception {
		String tag = tag();
		long id = create(seller, body(tag + " PlayStation 5", "electronics", "consoles", "ekb", 45000, "used", "private", true));

		mockMvc.perform(put("/favorites/" + id).with(as(buyer))).andExpect(status().isNoContent());
		mockMvc.perform(put("/favorites/" + id).with(as(buyer))).andExpect(status().isNoContent()); // repeat is fine
		mockMvc.perform(put("/favorites/999999999").with(as(buyer))).andExpect(status().isNotFound());

		// The heart shows only for the user who saved it
		mockMvc.perform(get("/listings").param("q", tag).with(as(buyer)))
			.andExpect(jsonPath("$.items[0].favorite").value(true))
			.andExpect(jsonPath("$.items[0].favoritesCount").value(1));
		mockMvc.perform(get("/listings").param("q", tag)).andExpect(jsonPath("$.items[0].favorite").value(false));

		mockMvc.perform(get("/favorites").with(as(buyer)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].id").value(id));

		mockMvc.perform(delete("/favorites/" + id).with(as(buyer))).andExpect(status().isNoContent());
		mockMvc.perform(get("/favorites").with(as(buyer))).andExpect(jsonPath("$.total").value(0));

		// Archived listings can't be newly saved
		mockMvc.perform(post("/listings/" + id + "/archive").with(as(seller))).andExpect(status().isOk());
		mockMvc.perform(put("/favorites/" + id).with(as(buyer))).andExpect(status().isNotFound());
	}

	/** A random word that can't appear in other tests' listings. */
	private static String tag() {
		return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
	}

	private static RequestPostProcessor as(UUID userId) {
		return jwt().jwt(token -> token.subject(userId.toString()));
	}

	private long create(UUID sellerId, String body) throws Exception {
		String response = mockMvc.perform(post("/listings").with(as(sellerId)).contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(response, "$.id")).longValue();
	}

	private List<Long> searchIds(String... params) throws Exception {
		var request = get("/listings");
		for (int i = 0; i < params.length; i += 2) {
			request.param(params[i], params[i + 1]);
		}
		String response = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString();
		List<Number> ids = JsonPath.read(response, "$.items[*].id");
		return ids.stream().map(Number::longValue).toList();
	}

	private static String body(String title, String category, String subcategory, String city, long price,
			String condition, String sellerType, boolean withPhoto) {
		return """
				{"categoryId": "%s", "subcategoryId": "%s", "cityId": "%s", "title": "%s",
				 "description": "Good condition, everything works. Pickup or delivery.",
				 "price": %d, "condition": "%s", "sellerType": "%s",
				 "photoUrls": %s, "contactByMessages": true, "contactByPhone": true}
				""".formatted(category, subcategory, city, title, price, condition, sellerType,
				withPhoto ? "[\"https://cdn.markethub.local/cover.jpg\"]" : "[]");
	}

}
