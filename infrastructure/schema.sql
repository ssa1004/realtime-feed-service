-- realtime-feed-service 의 PostgreSQL 스키마.
-- ADR-0009 — R2DBC 위에서 단일 테이블 구조.
CREATE TABLE IF NOT EXISTS feed_events (
    id           UUID PRIMARY KEY,
    sku_id       VARCHAR(64) NOT NULL,
    event_type   VARCHAR(32) NOT NULL,
    trade_id     UUID,
    price_krw    BIGINT,
    quantity     INT,
    occurred_at  TIMESTAMPTZ NOT NULL,
    sequence     BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_feed_events_sku_seq
    ON feed_events (sku_id, sequence DESC);

CREATE INDEX IF NOT EXISTS idx_feed_events_sku_time
    ON feed_events (sku_id, occurred_at);
