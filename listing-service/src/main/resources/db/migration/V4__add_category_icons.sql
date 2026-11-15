-- Phosphor CSS classes consumed by the frontend. Keep a generic icon for future categories.
ALTER TABLE categories
    ADD COLUMN icon VARCHAR(64) NOT NULL DEFAULT 'ph ph-tag';

UPDATE categories
SET icon = CASE id
    WHEN 'transport'   THEN 'ph ph-car'
    WHEN 'realty'      THEN 'ph ph-buildings'
    WHEN 'jobs'        THEN 'ph ph-briefcase'
    WHEN 'services'    THEN 'ph ph-wrench'
    WHEN 'electronics' THEN 'ph ph-device-mobile'
    WHEN 'home'        THEN 'ph ph-armchair'
    WHEN 'clothing'    THEN 'ph ph-t-shirt'
    WHEN 'kids'        THEN 'ph ph-baby'
    WHEN 'hobbies'     THEN 'ph ph-bicycle'
    WHEN 'pets'        THEN 'ph ph-paw-print'
    ELSE icon
END;
