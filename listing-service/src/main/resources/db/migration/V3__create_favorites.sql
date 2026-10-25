CREATE TABLE favorites (
    user_id    UUID        NOT NULL,
    listing_id BIGINT      NOT NULL REFERENCES listings (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, listing_id)
);

-- For counting how many users saved a listing
CREATE INDEX favorites_listing_idx ON favorites (listing_id);
