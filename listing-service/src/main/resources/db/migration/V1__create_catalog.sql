-- Reference data for listings: categories, subcategories and cities, named in English and Russian.
CREATE TABLE categories (
    id       VARCHAR(32) PRIMARY KEY,
    name_en  VARCHAR(64) NOT NULL,
    name_ru  VARCHAR(64) NOT NULL,
    position SMALLINT    NOT NULL
);

CREATE TABLE subcategories (
    id          VARCHAR(32) PRIMARY KEY,
    category_id VARCHAR(32) NOT NULL REFERENCES categories (id),
    name_en     VARCHAR(64) NOT NULL,
    name_ru     VARCHAR(64) NOT NULL,
    position    SMALLINT    NOT NULL,
    -- Lets listings reference (category, subcategory) as a pair, so they can't mismatch
    UNIQUE (category_id, id)
);

CREATE TABLE cities (
    id       VARCHAR(16) PRIMARY KEY,
    name_en  VARCHAR(64) NOT NULL,
    name_ru  VARCHAR(64) NOT NULL,
    position SMALLINT    NOT NULL
);

INSERT INTO categories (id, name_en, name_ru, position) VALUES
    ('transport',   'Transport',       'Транспорт',     1),
    ('realty',      'Real estate',     'Недвижимость',  2),
    ('jobs',        'Jobs',            'Работа',        3),
    ('services',    'Services',        'Услуги',        4),
    ('electronics', 'Electronics',     'Электроника',   5),
    ('home',        'Home & garden',   'Дом и сад',     6),
    ('clothing',    'Clothing',        'Одежда',        7),
    ('kids',        'Kids',            'Детям',         8),
    ('hobbies',     'Hobbies & sport', 'Хобби и спорт', 9),
    ('pets',        'Pets',            'Животные',      10);

INSERT INTO subcategories (id, category_id, name_en, name_ru, position) VALUES
    ('cars',          'transport',   'Cars',                  'Автомобили',                1),
    ('motorcycles',   'transport',   'Motorcycles',           'Мотоциклы',                 2),
    ('parts',         'transport',   'Parts & accessories',   'Запчасти и аксессуары',     3),
    ('apartments',    'realty',      'Apartments',            'Квартиры',                  1),
    ('houses',        'realty',      'Houses & cottages',     'Дома и дачи',               2),
    ('rooms',         'realty',      'Rooms',                 'Комнаты',                   3),
    ('vacancies',     'jobs',        'Vacancies',             'Вакансии',                  1),
    ('resumes',       'jobs',        'Resumes',               'Резюме',                    2),
    ('repair',        'services',    'Repair & construction', 'Ремонт и строительство',    1),
    ('cleaning',      'services',    'Cleaning',              'Уборка',                    2),
    ('beauty',        'services',    'Beauty & health',       'Красота и здоровье',        3),
    ('phones',        'electronics', 'Phones',                'Телефоны',                  1),
    ('laptops',       'electronics', 'Laptops',               'Ноутбуки',                  2),
    ('audio',         'electronics', 'Audio',                 'Аудио',                     3),
    ('tv',            'electronics', 'TV & video',            'Телевизоры',                4),
    ('consoles',      'electronics', 'Game consoles',         'Игровые приставки',         5),
    ('furniture',     'home',        'Furniture',             'Мебель',                    1),
    ('appliances',    'home',        'Appliances',            'Бытовая техника',           2),
    ('decor',         'home',        'Decor & lighting',      'Декор и освещение',         3),
    ('women',         'clothing',    'Women',                 'Женская одежда',            1),
    ('men',           'clothing',    'Men',                   'Мужская одежда',            2),
    ('shoes',         'clothing',    'Shoes',                 'Обувь',                     3),
    ('strollers',     'kids',        'Strollers & car seats', 'Коляски и автокресла',      1),
    ('toys',          'kids',        'Toys',                  'Игрушки',                   2),
    ('kidsclothes',   'kids',        'Kids clothing',         'Детская одежда',            3),
    ('kidstransport', 'kids',        'Bikes & scooters',      'Велосипеды и самокаты',     4),
    ('bikes',         'hobbies',     'Bicycles',              'Велосипеды',                1),
    ('music',         'hobbies',     'Musical instruments',   'Музыкальные инструменты',   2),
    ('sport',         'hobbies',     'Sports & fitness',      'Спорт и фитнес',            3),
    ('supplies',      'pets',        'Pet supplies',          'Товары для животных',       1),
    ('cats',          'pets',        'Cats',                  'Кошки',                     2),
    ('dogs',          'pets',        'Dogs',                  'Собаки',                    3);

INSERT INTO cities (id, name_en, name_ru, position) VALUES
    ('msk', 'Moscow',           'Москва',          1),
    ('spb', 'Saint Petersburg', 'Санкт-Петербург', 2),
    ('kzn', 'Kazan',            'Казань',          3),
    ('nsk', 'Novosibirsk',      'Новосибирск',     4),
    ('ekb', 'Yekaterinburg',    'Екатеринбург',    5);
