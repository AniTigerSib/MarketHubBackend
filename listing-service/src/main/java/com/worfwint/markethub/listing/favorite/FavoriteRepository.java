package com.worfwint.markethub.listing.favorite;

import java.sql.Types;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.worfwint.markethub.listing.listing.ListingDtos.ListingView;
import com.worfwint.markethub.listing.listing.ListingRepository;
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
	public Page<ListingView> findByUser(UUID userId, int page, int size) {
		long total = jdbc.sql("SELECT count(*) FROM favorites WHERE user_id = :userId")
			.param("userId", userId)
			.query(Long.class)
			.single();
		List<ListingView> items = jdbc.sql(ListingRepository.SELECT_LISTING
				+ """
						 JOIN favorites mine ON mine.listing_id = l.id AND mine.user_id = :userId
						 ORDER BY mine.created_at DESC, l.id DESC
						 LIMIT :limit OFFSET :offset
						""")
			.param("userId", userId)
			.param("viewerId", userId, Types.OTHER)
			.param("limit", size)
			.param("offset", (long) page * size)
			.query(ListingRepository::mapListing)
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
