package com.worfwint.markethub.listing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.worfwint.markethub.listing.favorite.FavoriteController;
import com.worfwint.markethub.listing.favorite.FavoriteRepository;
import com.worfwint.markethub.listing.listing.ListingController;
import com.worfwint.markethub.listing.listing.ListingDtos.ListingView;
import com.worfwint.markethub.listing.listing.ListingDtos.Page;
import com.worfwint.markethub.listing.listing.ListingRepository;
import com.worfwint.markethub.listing.listing.ListingService;
import com.worfwint.markethub.listing.security.SecurityConfig;

@WebMvcTest({ ListingController.class, FavoriteController.class })
@Import(SecurityConfig.class)
class ListingControllerTests {

	private static final UUID SELLER = UUID.fromString("0b6d1f3e-5c4a-4e2b-9f8d-7a6b5c4d3e2f");

	private static final String VALID_BODY = """
			{"categoryId": "electronics", "subcategoryId": "phones", "cityId": "msk",
			 "title": "iPhone 13, 128 GB, blue", "description": "Battery 89%, always in a case. Box included.",
			 "price": 41990, "condition": "used", "sellerType": "private",
			 "photoUrls": ["https://cdn.markethub.local/1.jpg"], "contactByMessages": true, "contactByPhone": false}
			""";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ListingService listingService;

	@MockitoBean
	private ListingRepository listingRepository;

	@MockitoBean
	private FavoriteRepository favoriteRepository;

	@Test
	void searchIsPublic() throws Exception {
		given(listingRepository.search(any(), isNull())).willReturn(new Page<>(List.of(), 0, 0, 24));

		mockMvc.perform(get("/listings").param("q", "iphone").param("condition", "new", "used").param("sort", "cheap"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(0))
			.andExpect(jsonPath("$.size").value(24));
	}

	@Test
	void rejectsInvalidSearchParameters() throws Exception {
		mockMvc.perform(get("/listings").param("sort", "random")).andExpect(status().isBadRequest());
		mockMvc.perform(get("/listings").param("size", "1000")).andExpect(status().isBadRequest());
	}

	@Test
	void creatingNeedsToken() throws Exception {
		mockMvc.perform(post("/listings").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void createsListingForTokenSubject() throws Exception {
		given(listingService.create(eq(SELLER), any())).willReturn(view());

		mockMvc.perform(post("/listings").with(jwt().jwt(token -> token.subject(SELLER.toString())))
				.contentType(MediaType.APPLICATION_JSON)
				.content(VALID_BODY))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").value(1))
			.andExpect(jsonPath("$.sellerId").value(SELLER.toString()));
	}

	@Test
	void rejectsInvalidListing() throws Exception {
		String invalid = """
				{"categoryId": "electronics", "subcategoryId": "phones", "cityId": "msk",
				 "title": "iPh", "description": "too short", "price": -5, "condition": "broken",
				 "sellerType": "private", "photoUrls": ["ftp://x"],
				 "contactByMessages": false, "contactByPhone": false}
				""";
		mockMvc.perform(post("/listings").with(jwt().jwt(token -> token.subject(SELLER.toString())))
				.contentType(MediaType.APPLICATION_JSON)
				.content(invalid))
			.andExpect(status().isBadRequest());

		verify(listingService, never()).create(any(), any());
	}

	@Test
	void editingNeedsToken() throws Exception {
		mockMvc.perform(put("/listings/1").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/listings/1/archive")).andExpect(status().isUnauthorized());
	}

	@Test
	void ownListingsAndFavoritesNeedToken() throws Exception {
		mockMvc.perform(get("/listings/mine")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/favorites")).andExpect(status().isUnauthorized());
		mockMvc.perform(put("/favorites/1")).andExpect(status().isUnauthorized());
	}

	private static ListingView view() {
		return new ListingView(1, SELLER, "electronics", "phones", "msk", "iPhone 13, 128 GB, blue",
				"Battery 89%, always in a case. Box included.", 41990, null, "used", "private",
				List.of("https://cdn.markethub.local/1.jpg"), true, false, "active", 0, false, Instant.now(),
				Instant.now());
	}

}
