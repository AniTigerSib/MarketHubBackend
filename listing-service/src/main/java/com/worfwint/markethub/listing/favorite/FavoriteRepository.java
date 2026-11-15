package com.worfwint.markethub.listing.favorite;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.worfwint.markethub.listing.listing.ListingDtos.FavoriteListingSummary;
import com.worfwint.markethub.listing.listing.ListingDtos.Page;

@Repository
public class FavoriteRepository {

	private final JdbcClient jdbc;

	public FavoriteRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Saves an active listing; repeating it is harmless. Returns false if no such active listing. */
	public boolean add(UUID userId, long listingId) {
		return jdbc.sql("""
				INSERT INTO favorites (user_id, listing_id)
				SELECT :userId, id FROM listings WHERE id = :listingId AND status = 'active'
				ON CONFLICT DO NOTHING
				""")
			.param("userId", userId)
			.param("listingId", listingId)
			.update() > 0 || exists(userId, listingId);
	}

	public void remove(UUID userId, long listingId) {
		jdbc.sql("DELETE FROM favorites WHERE user_id = :userId AND listing_id = :listingId")
			.param("userId", userId)
			.param("listingId", listingId)
			.update();
	}

	/** The user's saved listings, most recently saved first. Includes listings archived since. */
	public Page<FavoriteListingSummary> findByUser(UUID userId, int page, int size) {
		long total = jdbc.sql("SELECT count(*) FROM favorites WHERE user_id = :userId")
			.param("userId", userId)
			.query(Long.class)
			.single();
		List<FavoriteListingSummary> items = jdbc.sql("""
				SELECT l.id, l.title, l.price, l.price_unit, l.category_id, l.city_id, l.created_at,
				       l.photo_urls[1] AS cover_photo_url
				FROM listings l
				JOIN favorites mine ON mine.listing_id = l.id AND mine.user_id = :userId
				ORDER BY mine.created_at DESC, l.id DESC
				LIMIT :limit OFFSET :offset
				""")
			.param("userId", userId)
			.param("limit", size)
			.param("offset", (long) page * size)
			.query((rs, rowNum) -> new FavoriteListingSummary(rs.getLong("id"), rs.getString("title"),
					rs.getLong("price"), rs.getString("price_unit"), rs.getString("category_id"),
					rs.getString("city_id"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
					rs.getString("cover_photo_url"), true))
			.list();
		return new Page<>(items, total, page, size);
	}

	private boolean exists(UUID userId, long listingId) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM favorites WHERE user_id = :userId AND listing_id = :listingId)")
			.param("userId", userId)
			.param("listingId", listingId)
			.query(Boolean.class)
			.single();
	}

}
