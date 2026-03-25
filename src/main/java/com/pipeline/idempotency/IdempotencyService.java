package com.pipeline.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 멱등성 처리 서비스
 *
 * Consumer가 메시지를 처리할 때 이 서비스를 통해:
 * 1. 이미 처리한 이벤트인지 확인 (existsByEventId)
 * 2. 비즈니스 로직 실행
 * 3. event_handled에 기록 (같은 TX)
 *
 * 흐름:
 * ┌──────────────────────────────────────────────────┐
 * │  @Transactional                                   │
 * │                                                    │
 * │  ① event_handled에 eventId 존재? → 이미 처리됨   │
 * │     → return (중복 무시)                           │
 * │                                                    │
 * │  ② 비즈니스 로직 실행                             │
 * │                                                    │
 * │  ③ event_handled에 INSERT                         │
 * │     → 비즈니스 + 멱등 기록이 같은 TX              │
 * │                                                    │
 * │  ④ TX 커밋 → 둘 다 확정                          │
 * │     실패 시 → 둘 다 롤백 → 재처리 가능            │
 * └──────────────────────────────────────────────────┘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final EventHandledRepository eventHandledRepository;

    /**
     * 이벤트가 이미 처리되었는지 확인
     */
    public boolean isAlreadyHandled(String eventId) {
        return eventHandledRepository.existsByEventId(eventId);
    }

    /**
     * 이벤트 처리 완료 기록
     * 비즈니스 로직과 같은 @Transactional 안에서 호출해야 한다.
     *
     * UNIQUE 제약 조건이 있으므로 동시에 같은 eventId로 INSERT하면
     * DataIntegrityViolationException 발생 → 중복 방지
     */
    @Transactional
    public boolean markHandled(String eventId, String eventType) {
        try {
            eventHandledRepository.save(EventHandled.of(eventId, eventType));
            log.debug("[Idempotency] 처리 기록 — eventId={}, type={}", eventId, eventType);
            return true;
        } catch (DataIntegrityViolationException e) {
            // UNIQUE 제약 조건 위반 = 이미 다른 Consumer가 처리 중
            log.warn("[Idempotency] 중복 감지 (UNIQUE 위반) — eventId={}", eventId);
            return false;
        }
    }
}
