-- Stage 2: the core schema - users, orders, order_items, processed_events, outbox_events.
--
-- Sequences use `increment by 50` to match @SequenceGenerator(allocationSize = 50) in the
-- entities. These two numbers MUST stay equal: if the sequence increments by 1 while Hibernate
-- hands out 50 ids per fetch, the application generates ids the database will later reissue and
-- inserts start failing with duplicate key violations under concurrency.
--
-- SEQUENCE rather than IDENTITY (bigserial) on purpose. IDENTITY forces Hibernate to execute an
-- insert immediately to read the generated key back, which silently disables JDBC batching -
-- and batching order_items is exactly what stage 15 needs.

-- ---------------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------------
CREATE SEQUENCE users_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE users
(
    id            BIGINT       NOT NULL,
    username      VARCHAR(50)  NOT NULL,
    email         VARCHAR(255) NOT NULL,
    -- BCrypt hashes are 60 characters. The column is wider to leave room for a future
    -- algorithm change (Spring Security's DelegatingPasswordEncoder prefixes {id}) without a
    -- migration. Never sized to a plaintext password - none is ever stored.
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_username UNIQUE (username),
    -- Enumerated in the database as well as in Java. A new role becomes a deliberate migration
    -- instead of an unnoticed typo persisting as a role nobody has.
    CONSTRAINT ck_users_role CHECK (role IN ('CUSTOMER', 'SUPPORT', 'ADMIN'))
);

-- Functional unique index, not UNIQUE (email). Email is case-insensitive in practice, so
-- 'Ahmad@test.com' and 'ahmad@test.com' must collide - a plain unique constraint would let both
-- register and leave login ambiguous. The application still normalises to lower case on write;
-- this is the guarantee that survives an application bug.
-- Queries must therefore compare on lower(email) to use this index (findByEmailIgnoreCase).
CREATE UNIQUE INDEX uq_users_email_lower ON users (LOWER(email));

-- ---------------------------------------------------------------------------
-- orders
-- ---------------------------------------------------------------------------
CREATE SEQUENCE orders_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE orders
(
    id          BIGINT         NOT NULL,
    customer_id BIGINT         NOT NULL,
    status      VARCHAR(20)    NOT NULL,
    total_price NUMERIC(19, 2) NOT NULL,
    -- Optimistic locking (stage 17). Present from the first migration because backfilling a
    -- version column onto live rows is far more painful than having it early.
    version     BIGINT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES users (id),
    CONSTRAINT ck_orders_status CHECK (status IN
        ('CREATED', 'CONFIRMED', 'PROCESSING', 'SHIPPED', 'DELIVERED', 'CANCELLED')),
    -- Money is never negative here. A refund is a different transaction, not a negative order.
    CONSTRAINT ck_orders_total_price CHECK (total_price >= 0)
);

-- Covers both "list my orders, newest first" (GET /api/orders) and the foreign key check on
-- users, since customer_id is the leading column. PostgreSQL does not index foreign keys
-- automatically, so without this every delete of a user would sequentially scan orders.
CREATE INDEX ix_orders_customer_created_at ON orders (customer_id, created_at DESC);

-- SUPPORT and ADMIN filter by status; customers do not.
CREATE INDEX ix_orders_status ON orders (status);

-- ---------------------------------------------------------------------------
-- order_items
-- ---------------------------------------------------------------------------
CREATE SEQUENCE order_items_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE order_items
(
    id         BIGINT         NOT NULL,
    order_id   BIGINT         NOT NULL,
    product_id BIGINT         NOT NULL,
    quantity   INTEGER        NOT NULL,
    -- The price at the time of ordering, copied from the product service. Never joined back to
    -- a live product price: an order must not change retroactively when a product is repriced.
    unit_price NUMERIC(19, 2) NOT NULL,

    CONSTRAINT pk_order_items PRIMARY KEY (id),
    -- ON DELETE CASCADE mirrors orphanRemoval on the JPA side, so the database stays consistent
    -- even for deletes issued outside Hibernate.
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT ck_order_items_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_items_unit_price CHECK (unit_price >= 0),
    -- The same product twice in one order is a bug, not two line items: quantity should have
    -- been merged. Enforced here so the service layer cannot get it subtly wrong.
    CONSTRAINT uq_order_items_order_product UNIQUE (order_id, product_id)
);

CREATE INDEX ix_order_items_order_id ON order_items (order_id);

-- ---------------------------------------------------------------------------
-- processed_events  (Kafka idempotency, stage 12)
-- ---------------------------------------------------------------------------
-- event_id is the natural primary key, deliberately. Consumer idempotency then costs nothing
-- extra: inserting the event id in the same transaction as the side effect either succeeds, or
-- violates the primary key and tells us the event was already handled. No read-then-write race.
CREATE TABLE processed_events
(
    event_id     VARCHAR(100) NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_processed_events PRIMARY KEY (event_id)
);

-- ---------------------------------------------------------------------------
-- outbox_events  (Outbox pattern, stage 19)
-- ---------------------------------------------------------------------------
CREATE SEQUENCE outbox_events_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE outbox_events
(
    id             BIGINT       NOT NULL,
    -- Travels to Kafka as the event's identity and becomes the consumer's idempotency key in
    -- processed_events. Unique here so a retrying publisher cannot mint a second identity for
    -- the same event.
    event_id       UUID         NOT NULL,
    aggregate_type VARCHAR(50)  NOT NULL,
    -- VARCHAR, not BIGINT: keeps the outbox generic across aggregates whose ids are not longs.
    aggregate_id   VARCHAR(50)  NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        JSONB        NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts       INTEGER      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at   TIMESTAMPTZ,

    CONSTRAINT pk_outbox_events PRIMARY KEY (id),
    CONSTRAINT uq_outbox_events_event_id UNIQUE (event_id),
    CONSTRAINT ck_outbox_events_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_outbox_events_attempts CHECK (attempts >= 0),
    -- A published row must record when. Catches a publisher that marks rows PUBLISHED without
    -- actually completing the send.
    CONSTRAINT ck_outbox_events_published_at CHECK (
        (status = 'PUBLISHED' AND published_at IS NOT NULL) OR
        (status <> 'PUBLISHED' AND published_at IS NULL))
);

-- Partial index: the publisher only ever polls PENDING rows, oldest first. Restricting the index
-- to those rows keeps it small permanently, instead of growing with every event ever published.
CREATE INDEX ix_outbox_events_pending ON outbox_events (created_at) WHERE status = 'PENDING';
