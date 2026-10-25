CREATE TABLE listings (
    id                  BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    seller_id           UUID          NOT NULL, -- user id from auth-service (token subject)
    category_id         VARCHAR(32)   NOT NULL,
    subcategory_id      VARCHAR(32)   NOT NULL,
    city_id             VARCHAR(16)   NOT NULL REFERENCES cities (id),
    title               VARCHAR(100)  NOT NULL CHECK (char_length(title) >= 5),
    description         VARCHAR(4000) NOT NULL CHECK (char_length(description) >= 30),
    price               BIGINT        NOT NULL CHECK (price >= 0), -- whole rubles
    price_unit          VARCHAR(8)    CHECK (price_unit IN ('month', 'sqm')), -- null = one-off price
    item_condition      VARCHAR(8)    NOT NULL CHECK (item_condition IN ('new', 'used')),
    seller_type         VARCHAR(8)    NOT NULL CHECK (seller_type IN ('private', 'business')),
    -- First photo is the cover
    photo_urls          TEXT[]        NOT NULL DEFAULT '{}' CHECK (cardinality(photo_urls) <= 10),
    contact_by_messages BOOLEAN       NOT NULL,
    contact_by_phone    BOOLEAN       NOT NULL,
    -- 'review', 'rejected' and 'draft' will come with moderation
    status              VARCHAR(16)   NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'archived')),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- 'simple' config: no language-specific stemming, so Russian and English text both index as-is
    search_vector       TSVECTOR      GENERATED ALWAYS AS (to_tsvector('simple', title || ' ' || description)) STORED,

    FOREIGN KEY (category_id, subcategory_id) REFERENCES subcategories (category_id, id),
    CHECK (contact_by_messages OR contact_by_phone)
);

CREATE INDEX listings_search_idx ON listings USING GIN (search_vector);
CREATE INDEX listings_browse_idx ON listings (status, category_id, subcategory_id, created_at DESC);
CREATE INDEX listings_price_idx ON listings (status, price);
CREATE INDEX listings_seller_idx ON listings (seller_id, status);
