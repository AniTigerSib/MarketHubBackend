package com.worfwint.markethub.listing.listing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.worfwint.markethub.listing.listing.ListingDtos.ListingRequest;
import com.worfwint.markethub.listing.listing.ListingDtos.ListingView;
import com.worfwint.markethub.listing.listing.ListingDtos.Page;
import com.worfwint.markethub.listing.listing.ListingDtos.SearchCriteria;

@Repository
public class ListingRepository {

	/** Listing columns plus favorites count and whether {@code :viewerId} saved it. */
	public static final String SELECT_LISTING = """
			SELECT l.id, l.seller_id, l.category_id, l.subcategory_id, l.city_id, l.title, l.description,
			       l.price, l.price_unit, l.item_condition, l.seller_type, l.photo_urls,
			       l.contact_by_messages, l.contact_by_phone, l.status, l.created_at, l.updated_at,
			       (SELECT count(*) FROM favorites f WHERE f.listing_id = l.id) AS favorites_count,
			       EXISTS (SELECT 1 FROM favorites f
			               WHERE f.listing_id = l.id AND f.user_id = CAST(:viewerId AS uuid)) AS favorite
			FROM listings l
			""";

	private static final java.util.regex.Pattern WORD = java.util.regex.Pattern.compile("[\\p{L}\\p{N}]+");

	private final JdbcClient jdbc;

	public ListingRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public long insert(UUID sellerId, ListingRequest request) {
		return jdbc.sql("""
				INSERT INTO listings (seller_id, category_id, subcategory_id, city_id, title, description, price,
				                      price_unit, item_condition, seller_type, photo_urls, contact_by_messages,
				                      contact_by_phone)
				VALUES (:sellerId, :categoryId, :subcategoryId, :cityId, :title, :description, :price,
				        :priceUnit, :condition, :sellerType, :photoUrls, :contactByMessages, :contactByPhone)
				RETURNING id
				""")
			.param("sellerId", sellerId)
			.params(fields(request))
			.query(Long.class)
			.single();
	}

	public void update(long id, ListingRequest request) {
		jdbc.sql("""
				UPDATE listings
				SET category_id = :categoryId, subcategory_id = :subcategoryId, city_id = :cityId,
				    title = :title, description = :description, price = :price, price_unit = :priceUnit,
				    item_condition = :condition, seller_type = :sellerType, photo_urls = :photoUrls,
				    contact_by_messages = :contactByMessages, contact_by_phone = :contactByPhone,
				    updated_at = now()
				WHERE id = :id
				""")
			.param("id", id)
			.params(fields(request))
			.update();
	}

	public void updateStatus(long id, String status) {
		jdbc.sql("UPDATE listings SET status = :status, updated_at = now() WHERE id = :id AND status <> :status")
			.param("id", id)
			.param("status", status)
			.update();
	}

	public Optional<ListingView> findById(long id, UUID viewerId) {
		return jdbc.sql(SELECT_LISTING + " WHERE l.id = :id")
			.param("id", id)
			.param("viewerId", viewerId, Types.OTHER)
			.query(ListingRepository::mapListing)
			.optional();
	}

	/** Active listings matching every given filter, sorted and paginated. */
	public Page<ListingView> search(SearchCriteria criteria, UUID viewerId) {
		StringBuilder where = new StringBuilder(" WHERE l.status = 'active'");
		Map<String, Object> params = new LinkedHashMap<>();

		String tsQuery = toPrefixQuery(criteria.q());
		if (tsQuery != null) {
			where.append(" AND l.search_vector @@ to_tsquery('simple', :tsQuery)");
			params.put("tsQuery", tsQuery);
		}
		if (criteria.category() != null) {
			where.append(" AND l.category_id = :category");
			params.put("category", criteria.category());
		}
		if (criteria.subcategory() != null) {
			where.append(" AND l.subcategory_id = :subcategory");
			params.put("subcategory", criteria.subcategory());
		}
		if (criteria.city() != null) {
			where.append(" AND l.city_id = :city");
			params.put("city", criteria.city());
		}
		if (criteria.minPrice() != null) {
			where.append(" AND l.price >= :minPrice");
			params.put("minPrice", criteria.minPrice());
		}
		if (criteria.maxPrice() != null) {
			where.append(" AND l.price <= :maxPrice");
			params.put("maxPrice", criteria.maxPrice());
		}
		if (!criteria.condition().isEmpty()) {
			where.append(" AND l.item_condition IN (:conditions)"); // list expands to (?, ?)
			params.put("conditions", criteria.condition());
		}
		if (criteria.sellerType() != null) {
			where.append(" AND l.seller_type = :sellerType");
			params.put("sellerType", criteria.sellerType());
		}
		if (Boolean.TRUE.equals(criteria.withPhoto())) {
			where.append(" AND cardinality(l.photo_urls) > 0");
		}

		String orderBy = switch (criteria.sort()) {
			case "cheap" -> " ORDER BY l.price ASC, l.id DESC";
			case "expensive" -> " ORDER BY l.price DESC, l.id DESC";
			default -> " ORDER BY l.created_at DESC, l.id DESC";
		};

		long total = jdbc.sql("SELECT count(*) FROM listings l" + where).params(params).query(Long.class).single();
		List<ListingView> items = jdbc.sql(SELECT_LISTING + where + orderBy + " LIMIT :limit OFFSET :offset")
			.params(params)
			.param("viewerId", viewerId, Types.OTHER)
			.param("limit", criteria.size())
			.param("offset", (long) criteria.page() * criteria.size())
			.query(ListingRepository::mapListing)
			.list();
		return new Page<>(items, total, criteria.page(), criteria.size());
	}

	/** The seller's own listings, newest first; {@code status} null means all. */
	public List<ListingView> findBySeller(UUID sellerId, String status) {
		return jdbc.sql(SELECT_LISTING + """
				 WHERE l.seller_id = :sellerId AND (CAST(:status AS varchar) IS NULL OR l.status = :status)
				 ORDER BY l.created_at DESC, l.id DESC
				""")
			.param("sellerId", sellerId)
			.param("viewerId", sellerId, Types.OTHER)
			.param("status", status, Types.VARCHAR)
			.query(ListingRepository::mapListing)
			.list();
	}

	public Map<String, Long> countBySellerPerStatus(UUID sellerId) {
		Map<String, Long> counts = new LinkedHashMap<>(Map.of("active", 0L, "archived", 0L));
		jdbc.sql("SELECT status, count(*) AS n FROM listings WHERE seller_id = :sellerId GROUP BY status")
			.param("sellerId", sellerId)
			.query((rs, rowNum) -> counts.put(rs.getString("status"), rs.getLong("n")))
			.list();
		return counts;
	}

	/**
	 * Turns free text into a prefix query: "iphone 12" becomes "iphone:* & 12:*", so
	 * partially typed words match. Only letters and digits survive, which also keeps
	 * tsquery syntax characters out. Returns null if nothing searchable is left.
	 */
	static String toPrefixQuery(String text) {
		if (text == null) {
			return null;
		}
		List<String> terms = new ArrayList<>();
		Matcher matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
		while (matcher.find() && terms.size() < 10) {
			terms.add(matcher.group() + ":*");
		}
		return terms.isEmpty() ? null : String.join(" & ", terms);
	}

	private static Map<String, Object> fields(ListingRequest request) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("categoryId", request.categoryId());
		fields.put("subcategoryId", request.subcategoryId());
		fields.put("cityId", request.cityId());
		fields.put("title", request.title().strip());
		fields.put("description", request.description().strip());
		fields.put("price", request.price());
		fields.put("priceUnit", request.priceUnit());
		fields.put("condition", request.condition());
		fields.put("sellerType", request.sellerType());
		fields.put("photoUrls", request.photoUrls().toArray(String[]::new));
		fields.put("contactByMessages", request.contactByMessages());
		fields.put("contactByPhone", request.contactByPhone());
		return fields;
	}

	public static ListingView mapListing(ResultSet rs, int rowNum) throws SQLException {
		String[] photos = (String[]) rs.getArray("photo_urls").getArray();
		return new ListingView(rs.getLong("id"), rs.getObject("seller_id", UUID.class), rs.getString("category_id"),
				rs.getString("subcategory_id"), rs.getString("city_id"), rs.getString("title"),
				rs.getString("description"), rs.getLong("price"), rs.getString("price_unit"),
				rs.getString("item_condition"), rs.getString("seller_type"), List.of(photos),
				rs.getBoolean("contact_by_messages"), rs.getBoolean("contact_by_phone"), rs.getString("status"),
				rs.getLong("favorites_count"), rs.getBoolean("favorite"),
				rs.getObject("created_at", OffsetDateTime.class).toInstant(),
				rs.getObject("updated_at", OffsetDateTime.class).toInstant());
	}

}
