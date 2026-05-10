package com.example.feed.adapter.outbound.redis

import com.example.feed.application.port.FeedCache
import com.example.feed.domain.FeedEvent
import com.example.feed.domain.SkuId
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Component

/**
 * [FeedCache] 의 Redis 구현. SortedSet `feed:recent:{sku}` 를 쓴다 — score 는 sequence.
 *
 * 한 SKU 의 cache 는 최근 [MAX_CACHE_SIZE] 건으로 제한 — `ZADD` 후 `ZREMRANGEBYRANK` 로
 * 오래된 것 자른다. 메모리 점유 일정 (예측 가능).
 *
 * 직렬화: Jackson JSON. 도메인이 sealed class 이므로 typeId 가 필요한데, 현재는 cache 가
 * `TradeMatched` 만 다루므로 단순 사용. BidAskUpdated / Heartbeat 가 cache 대상이 되면
 * polymorphic 직렬화 추가.
 */
@Component
class FeedRedisCacheAdapter(
    factory: ReactiveRedisConnectionFactory,
    private val mapper: ObjectMapper,
) : FeedCache {

    private val log = LoggerFactory.getLogger(FeedRedisCacheAdapter::class.java)
    private val redis = ReactiveStringRedisTemplate(factory)

    override suspend fun cache(event: FeedEvent) {
        if (event !is FeedEvent.TradeMatched) return
        val key = keyFor(event.skuId)
        val payload = mapper.writeValueAsString(event)
        runCatching {
            redis.opsForZSet()
                .add(key, payload, event.sequence.value.toDouble())
                .awaitSingleOrNull()
            // 최근 MAX_CACHE_SIZE 건만 유지 — 더 오래된 score 의 멤버 제거.
            redis.opsForZSet()
                .removeRange(key, org.springframework.data.domain.Range.closed(0L, -(MAX_CACHE_SIZE + 1)))
                .awaitSingleOrNull()
        }.onFailure { log.warn("redis cache 실패 sku={}: {}", event.skuId.value, it.message) }
    }

    override suspend fun recent(skuId: SkuId, limit: Int): List<FeedEvent> {
        val key = keyFor(skuId)
        val items = runCatching {
            redis.opsForZSet()
                .reverseRange(key, org.springframework.data.domain.Range.closed(0L, limit.toLong() - 1))
                .collectList()
                .awaitSingleOrNull()
        }.getOrNull() ?: return emptyList()
        return items.mapNotNull { json ->
            runCatching { mapper.readValue<FeedEvent.TradeMatched>(json) }.getOrNull()
        }
    }

    private fun keyFor(skuId: SkuId): String = "feed:recent:${skuId.value}"

    companion object {
        const val MAX_CACHE_SIZE = 200L
    }
}
