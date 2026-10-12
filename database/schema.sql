-- Smart Service Hub — MySQL 8 схемасы (JPA entity-лерімен сәйкес).
-- Қолданба ddl-auto=update арқылы кестелерді өзі де жасай алады; бұл файл құжаттама және қолмен орнату үшін.
CREATE DATABASE IF NOT EXISTS smart_service_hub CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE smart_service_hub;

CREATE TABLE IF NOT EXISTS service_categories (
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    name_kk VARCHAR(100) NOT NULL,
    name_ru VARCHAR(100) NOT NULL
);

CREATE TABLE IF NOT EXISTS users (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    full_name         VARCHAR(100) NOT NULL,
    phone             VARCHAR(20)  NOT NULL UNIQUE,
    email             VARCHAR(120) UNIQUE,
    password_hash     VARCHAR(100) NOT NULL,                       -- BCrypt
    role              VARCHAR(10)  NOT NULL,                       -- CLIENT | WORKER | ADMIN
    active            BIT          NOT NULL DEFAULT 1,
    specialization_id BIGINT NULL,                                 -- тек WORKER
    experience_years  INT NULL,
    district          VARCHAR(30) NULL,
    bio               VARCHAR(500) NULL,
    available         BIT          NOT NULL DEFAULT 1,
    rating_avg        DOUBLE       NOT NULL DEFAULT 0,
    rating_count      INT          NOT NULL DEFAULT 0,
    created_at        DATETIME(6)  NOT NULL,
    CONSTRAINT fk_users_specialization FOREIGN KEY (specialization_id) REFERENCES service_categories (id),
    INDEX idx_users_role (role)
);

CREATE TABLE IF NOT EXISTS services (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    category_id    BIGINT        NOT NULL,
    name_kk        VARCHAR(150)  NOT NULL,
    name_ru        VARCHAR(150)  NOT NULL,
    description_kk VARCHAR(1000) NULL,
    description_ru VARCHAR(1000) NULL,
    price          DECIMAL(10,2) NOT NULL,
    duration_minutes INT         NULL,
    active         BIT           NOT NULL DEFAULT 1,
    CONSTRAINT fk_services_category FOREIGN KEY (category_id) REFERENCES service_categories (id),
    INDEX idx_services_category (category_id)
);

CREATE TABLE IF NOT EXISTS orders (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    client_id      BIGINT        NOT NULL,
    worker_id      BIGINT        NULL,
    service_id     BIGINT        NOT NULL,
    address        VARCHAR(255)  NOT NULL,
    district       VARCHAR(30)   NOT NULL,
    scheduled_time DATETIME(6)   NOT NULL,
    note           VARCHAR(500)  NULL,
    status         VARCHAR(20)   NOT NULL,                         -- NEW | ACCEPTED | IN_PROGRESS | COMPLETED
    price          DECIMAL(10,2) NOT NULL,
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,
    CONSTRAINT fk_orders_client  FOREIGN KEY (client_id)  REFERENCES users (id),
    CONSTRAINT fk_orders_worker  FOREIGN KEY (worker_id)  REFERENCES users (id),
    CONSTRAINT fk_orders_service FOREIGN KEY (service_id) REFERENCES services (id),
    INDEX idx_orders_status  (status),
    INDEX idx_orders_client  (client_id),
    INDEX idx_orders_worker  (worker_id),
    INDEX idx_orders_created (created_at)
);

CREATE TABLE IF NOT EXISTS reviews (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id    BIGINT       NOT NULL,
    client_id   BIGINT       NOT NULL,
    worker_id   BIGINT       NOT NULL,
    rating      INT          NOT NULL,                             -- 1..5
    review_text VARCHAR(500) NULL,
    created_at  DATETIME(6)  NOT NULL,
    CONSTRAINT uq_reviews_order UNIQUE (order_id),
    CONSTRAINT chk_reviews_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT fk_reviews_order  FOREIGN KEY (order_id)  REFERENCES orders (id),
    CONSTRAINT fk_reviews_client FOREIGN KEY (client_id) REFERENCES users (id),
    CONSTRAINT fk_reviews_worker FOREIGN KEY (worker_id) REFERENCES users (id),
    INDEX idx_reviews_worker (worker_id)
);

CREATE TABLE IF NOT EXISTS payments (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id        BIGINT        NOT NULL,
    amount          DECIMAL(10,2) NOT NULL,
    status          VARCHAR(10)   NOT NULL,                        -- PENDING | PAID | FAILED
    transaction_ref VARCHAR(64)   NULL,
    card_last4      VARCHAR(4)    NULL,
    -- Duplicate-payment guard: equals order_id while the payment is PENDING or PAID, NULL for FAILED attempts.
    -- A UNIQUE index ignores NULLs, so an order can have many failed attempts but only one live/successful payment.
    active_order_id BIGINT        NULL,
    created_at      DATETIME(6)   NOT NULL,
    CONSTRAINT fk_payments_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT uq_payments_active_order UNIQUE (active_order_id),
    INDEX idx_payments_order (order_id)
);

-- Specialists who declined an order; automatic re-assignment never offers the order to them again
CREATE TABLE IF NOT EXISTS order_rejections (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id   BIGINT      NOT NULL,
    worker_id  BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_rejections_order  FOREIGN KEY (order_id)  REFERENCES orders (id),
    CONSTRAINT fk_rejections_worker FOREIGN KEY (worker_id) REFERENCES users (id),
    INDEX idx_rejections_order (order_id)
);

CREATE TABLE IF NOT EXISTS notifications (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    order_id   BIGINT       NULL,
    message_kk VARCHAR(500) NOT NULL,
    message_ru VARCHAR(500) NOT NULL,
    is_seen    BIT          NOT NULL DEFAULT 0,
    created_at DATETIME(6)  NOT NULL,
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_notifications_user (user_id)
);

-- Аудит журналы (FR-15)
CREATE TABLE IF NOT EXISTS order_status_history (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id   BIGINT      NOT NULL,
    old_status VARCHAR(20) NULL,
    new_status VARCHAR(20) NOT NULL,
    changed_by BIGINT      NULL,                                   -- NULL = жүйе
    changed_at DATETIME(6) NOT NULL,
    note       VARCHAR(255) NULL,
    CONSTRAINT fk_history_order FOREIGN KEY (order_id)   REFERENCES orders (id),
    CONSTRAINT fk_history_user  FOREIGN KEY (changed_by) REFERENCES users (id),
    INDEX idx_history_order (order_id)
);
