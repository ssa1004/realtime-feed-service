package com.example.feed.adapter.outbound.kafka

import com.example.feed.application.usecase.IngestTradeMatchedUseCase
import com.example.feed.domain.FeedEvent
import com.example.feed.domain.Money
import com.example.feed.domain.Sequence
import com.example.feed.domain.SkuId
import com.example.feed.domain.TradeId
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import reactor.kafka.receiver.KafkaReceiver
import reactor.kafka.receiver.ReceiverOptions
import reactor.kafka.receiver.ReceiverRecord
import java.time.Instant
import java.util.UUID

/**
 * resell-orderbook 이 발행하는 `trade.matched` 토픽 consumer.
 *
 * Reactor Kafka 의 [KafkaReceiver] 를 쓴다 — Spring Kafka 와 달리 한 토픽 = 한 Flux 로
 * 추상화돼 backpressure 를 자연스럽게 다룰 수 있다 (ADR-0007).
 *
 * 메시지 처리:
 *   1) JSON 역직렬화 → 도메인 [FeedEvent.TradeMatched] 로 변환
 *   2) [IngestTradeMatchedUseCase] 호출
 *   3) offset commit (수동 — 처리 성공한 offset 만 commit, at-least-once)
 *
 * 처리 실패 시 메시지를 skip 하고 commit (DLQ 는 ADR-0004 에서 별도 논의). 단순화 — 본 repo
 * 는 *학습용* feed view 이므로 손실 가능성을 감내한다. 실제 운영 service 라면 retry topic
 * 패턴을 권장.
 */
@Component
@ConditionalOnProperty(name = ["feed.kafka.enabled"], havingValue = "true", matchIfMissing = false)
class TradeMatchedConsumer(
    private val useCase: IngestTradeMatchedUseCase,
    private val mapper: ObjectMapper,
    @Value("\${feed.kafka.bootstrap}") private val bootstrap: String,
    @Value("\${feed.kafka.topic.trade-matched}") private val topic: String,
    @Value("\${feed.kafka.group-id}") private val groupId: String,
) {
    private val log = LoggerFactory.getLogger(TradeMatchedConsumer::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @PostConstruct
    fun start() {
        val options = ReceiverOptions.create<String, String>(
            mapOf(
                "bootstrap.servers" to bootstrap,
                "group.id" to groupId,
                "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
                "value.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
                "enable.auto.commit" to "false",
                "auto.offset.reset" to "latest",
            )
        ).subscription(listOf(topic))

        val receiver: KafkaReceiver<String, String> = KafkaReceiver.create(options)
        val flow: Flow<ReceiverRecord<String, String>> = receiver.receive().asFlow()

        job = scope.launch {
            flow.collect { record -> handle(record) }
        }
        log.info("trade.matched consumer 시작 topic={} group={}", topic, groupId)
    }

    private suspend fun handle(record: ReceiverRecord<String, String>) {
        try {
            val event = parse(record.value())
            useCase.handle(event)
        } catch (e: Exception) {
            log.warn("trade.matched 처리 실패 — skip offset={} err={}", record.offset(), e.message)
        } finally {
            record.receiverOffset().acknowledge()
        }
    }

    /**
     * resell-orderbook 의 outbox 메시지 형식 가정:
     * ```
     * { "skuId": "...", "tradeId": "uuid", "price": 150000, "quantity": 1,
     *   "occurredAt": "2026-05-09T...", "sequence": 42 }
     * ```
     */
    private fun parse(json: String): FeedEvent.TradeMatched {
        val node = mapper.readTree(json)
        return FeedEvent.TradeMatched(
            skuId = SkuId(node["skuId"].asText()),
            tradeId = TradeId(UUID.fromString(node["tradeId"].asText())),
            price = Money(node["price"].asLong()),
            quantity = node["quantity"].asInt(),
            occurredAt = Instant.parse(node["occurredAt"].asText()),
            sequence = Sequence(node["sequence"].asLong()),
        )
    }

    @PreDestroy
    fun stop() {
        log.info("trade.matched consumer 종료")
        scope.cancel()
        job = null
    }
}
