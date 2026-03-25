package com.pipeline.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface EventHandledRepository extends JpaRepository<EventHandled, Long> {

    boolean existsByEventId(String eventId);

    List<EventHandled> findByHandledAtBefore(LocalDateTime before);
}
