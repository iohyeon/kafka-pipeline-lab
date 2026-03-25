package com.pipeline.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreatedEvent {

    private Long orderId;
    private Long userId;
    private BigDecimal totalAmount;
    private String eventId;          // 멱등성 처리용 unique ID
    private LocalDateTime occurredAt;
}
