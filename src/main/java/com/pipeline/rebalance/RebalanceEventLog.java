package com.pipeline.rebalance;

import org.apache.kafka.common.TopicPartition;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 리밸런싱 이벤트 로그 — Eager vs Cooperative 비교 관찰용
 *
 * ConsumerRebalanceListener 콜백에서 발생하는 이벤트를 시간순으로 기록한다.
 * API로 조회하면 두 프로토콜의 동작 차이를 명확히 비교할 수 있다.
 */
@Component
public class RebalanceEventLog {

    private static final int MAX_EVENTS = 200;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final Deque<RebalanceEvent> events = new ConcurrentLinkedDeque<>();

    public record RebalanceEvent(
            String timestamp,
            long epochMs,
            String protocol,
            String consumerId,
            String eventType,
            List<String> partitions
    ) {}

    public void log(String protocol, String consumerId, String eventType,
                    Collection<TopicPartition> partitions) {
        events.addLast(new RebalanceEvent(
                LocalDateTime.now().format(FORMATTER),
                System.currentTimeMillis(),
                protocol,
                consumerId,
                eventType,
                partitions.stream()
                        .map(tp -> "P" + tp.partition())
                        .sorted()
                        .toList()
        ));
        while (events.size() > MAX_EVENTS) {
            events.pollFirst();
        }
    }

    public List<RebalanceEvent> getAll() {
        return new ArrayList<>(events);
    }

    public List<RebalanceEvent> getByProtocol(String protocol) {
        return events.stream()
                .filter(e -> e.protocol().equalsIgnoreCase(protocol))
                .toList();
    }

    public void clear() {
        events.clear();
    }
}
