package com.pipeline.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * PENDING 상태의 이벤트를 생성순으로 조회 (Polling 대상)
     * limit으로 한 번에 처리할 최대 건수를 제한한다.
     */
    @Query("SELECT o FROM OutboxEvent o WHERE o.status = 'PENDING' ORDER BY o.createdAt ASC LIMIT :limit")
    List<OutboxEvent> findPendingEvents(@Param("limit") int limit);

    /**
     * FAILED 상태이면서 재시도 횟수가 maxRetry 미만인 이벤트 조회
     */
    @Query("SELECT o FROM OutboxEvent o WHERE o.status = 'FAILED' AND o.retryCount < :maxRetry ORDER BY o.createdAt ASC LIMIT :limit")
    List<OutboxEvent> findRetryableEvents(@Param("maxRetry") int maxRetry, @Param("limit") int limit);

    /**
     * 발행 완료된 오래된 이벤트 삭제 (정리용)
     */
    @Query("SELECT o FROM OutboxEvent o WHERE o.status = 'PUBLISHED' AND o.publishedAt < :before")
    List<OutboxEvent> findPublishedBefore(@Param("before") LocalDateTime before);

    /**
     * 상태별 건수 조회 (모니터링용)
     */
    long countByStatus(OutboxStatus status);
}
