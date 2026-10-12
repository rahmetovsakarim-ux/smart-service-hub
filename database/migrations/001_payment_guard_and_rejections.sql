-- Upgrade for databases created before the duplicate-payment guard and specialist-rejection memory existed.
-- (Fresh installs use schema.sql, which already contains this. With DDL_AUTO=update Hibernate adds the column,
--  the unique index and the table itself; the backfill below is still needed for old PAID rows.)
USE smart_service_hub;

ALTER TABLE payments ADD COLUMN IF NOT EXISTS active_order_id BIGINT NULL;

-- Old successful payments become "live" payments. If this fails with a duplicate-key error the order has two
-- PAID rows (the bug this guard prevents): inspect them and mark the extra one as FAILED, then rerun.
UPDATE payments SET active_order_id = order_id WHERE status = 'PAID' AND active_order_id IS NULL;

ALTER TABLE payments ADD CONSTRAINT uq_payments_active_order UNIQUE (active_order_id);

CREATE TABLE IF NOT EXISTS order_rejections (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id   BIGINT      NOT NULL,
    worker_id  BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_rejections_order  FOREIGN KEY (order_id)  REFERENCES orders (id),
    CONSTRAINT fk_rejections_worker FOREIGN KEY (worker_id) REFERENCES users (id),
    INDEX idx_rejections_order (order_id)
);
