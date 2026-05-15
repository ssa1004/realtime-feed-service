package com.example.feed.adapter.outbound.r2dbc

import com.example.feed.application.port.FeedEventStore
import com.example.feed.domain.FeedEvent
import com.example.feed.domain.Money
import com.example.feed.domain.FeedSequence
import com.example.feed.domain.SkuId
import com.example.feed.domain.TradeId
import io.r2dbc.spi.Row
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * [FeedEventStore] 의 R2DBC 구현. 단일 테이블 `feed_events` 가정.
 *
 * 스키마 (DDL):
 * ```sql
 * CREATE TABLE feed_events (
 *     id           UUID PRIMARY KEY,
 *     sku_id       VARCHAR(64) NOT NULL,
 *     event_type   VARCHAR(32) NOT NULL,
 *     trade_id     UUID,
 *     price_krw    BIGINT,
 *     quantity     INT,
 *     occurred_at  TIMESTAMPTZ NOT NULL,
 *     sequence     BIGINT NOT NULL
 * );
 * CREATE INDEX idx_feed_events_sku_seq ON feed_events (sku_id, sequence DESC);
 * CREATE INDEX idx_feed_events_sku_time ON feed_events (sku_id, occurred_at);
 * ```
 *
 * R2DBC 선택 이유는 ADR-0009. JPA 와 달리 connection 을 long-running 으로 잡지 않으므로
 * Kafka consumer 처리량 (초당 N 천 건) 이 connection pool 을 압박하지 않는다.
 */
@Component
class FeedEventR2dbcAdapter(
    private val client: DatabaseClient,
) : FeedEventStore {

    private val log = LoggerFactory.getLogger(FeedEventR2dbcAdapter::class.java)

    override suspend fun append(event: FeedEvent) {
        when (event) {
            is FeedEvent.TradeMatched -> appendTradeMatched(event)
            is FeedEvent.BidAskUpdated -> {
                // 본 repo 는 BidAskUpdated 영속을 다루지 않는다 (호가창 스냅샷은 별도 viewmodel).
                log.debug("BidAskUpdated 영속 skip sku={}", event.skuId.value)
            }
            is FeedEvent.Heartbeat -> Unit  // heartbeat 은 영속하지 않음
        }
    }

    private suspend fun appendTradeMatched(event: FeedEvent.TradeMatched) {
        client.sql(
            """
            INSERT INTO feed_events
              (id, sku_id, event_type, trade_id, price_krw, quantity, occurred_at, sequence)
            VALUES (:id, :sku, 'TRADE_MATCHED', :trade, :price, :qty, :ts, :seq)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent()
        )
            .bind("id", UUID.randomUUID())
            .bind("sku", event.skuId.value)
            .bind("trade", event.tradeId.value)
            .bind("price", event.price.krw)
            .bind("qty", event.quantity)
            .bind("ts", event.occurredAt)
            .bind("seq", event.sequence.value)
            .fetch()
            .rowsUpdated()
            .awaitSingleOrNull()
    }

    override fun recent(skuId: SkuId, limit: Int): Flow<FeedEvent> =
        client.sql(
            """
            SELECT sku_id, trade_id, price_krw, quantity, occurred_at, sequence
            FROM feed_events
            WHERE sku_id = :sku AND event_type = 'TRADE_MATCHED'
            ORDER BY sequence DESC
            LIMIT :limit
            """.trimIndent()
        )
            .bind("sku", skuId.value)
            .bind("limit", limit)
            .map { row, _ -> mapTradeMatched(row) }
            .all()
            .asFlow()

    override fun between(skuId: SkuId, from: Instant, to: Instant): Flow<FeedEvent> =
        client.sql(
            """
            SELECT sku_id, trade_id, price_krw, quantity, occurred_at, sequence
            FROM feed_events
            WHERE sku_id = :sku
              AND event_type = 'TRADE_MATCHED'
              AND occurred_at >= :from AND occurred_at < :to
            ORDER BY occurred_at ASC
            """.trimIndent()
        )
            .bind("sku", skuId.value)
            .bind("from", from)
            .bind("to", to)
            .map { row, _ -> mapTradeMatched(row) }
            .all()
            .asFlow()

    private fun mapTradeMatched(row: Row): FeedEvent = FeedEvent.TradeMatched(
        skuId = SkuId(row.get("sku_id", String::class.java)!!),
        tradeId = TradeId(row.get("trade_id", UUID::class.java)!!),
        price = Money(row.get("price_krw", java.lang.Long::class.java)!!.toLong()),
        quantity = row.get("quantity", Integer::class.java)!!.toInt(),
        occurredAt = row.get("occurred_at", Instant::class.java)!!,
        sequence = FeedSequence(row.get("sequence", java.lang.Long::class.java)!!.toLong()),
    )
}
