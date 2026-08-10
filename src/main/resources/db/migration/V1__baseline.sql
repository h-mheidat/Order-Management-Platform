-- Baseline migration.
--
-- Intentionally creates nothing: stage 1 is infrastructure only, there are no entities yet.
-- Its purpose is to establish Flyway ownership of the schema from the very first run, so the
-- flyway_schema_history table exists and stage 2 (users, orders, order_items, processed_events,
-- outbox_events) starts at V2 instead of retrofitting a baseline over a Hibernate-generated
-- schema.
SELECT 1;
