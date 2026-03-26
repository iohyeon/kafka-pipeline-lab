package com.pipeline.api;

import com.pipeline.comparison.TransactionPhaseComparisonService;
import com.pipeline.outbox.OutboxEventRepository;
import com.pipeline.outbox.OutboxStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BEFORE_COMMIT vs AFTER_COMMIT vs 직접 호출 — 비교 실험 API
 */
@RestController
@RequestMapping("/api/comparison")
@RequiredArgsConstructor
public class ComparisonController {

    private final TransactionPhaseComparisonService comparisonService;
    private final OutboxEventRepository outboxEventRepository;

    @PostMapping("/before-commit/{orderId}")
    public Map<String, Object> testBeforeCommit(@PathVariable Long orderId) {
        String method = comparisonService.methodA_BeforeCommit(orderId);
        return buildResult(method, orderId);
    }

    @PostMapping("/after-commit/{orderId}")
    public Map<String, Object> testAfterCommit(@PathVariable Long orderId) {
        String method = comparisonService.methodB_AfterCommit(orderId);
        return buildResult(method, orderId);
    }

    @PostMapping("/direct-call/{orderId}")
    public Map<String, Object> testDirectCall(@PathVariable Long orderId) {
        String method = comparisonService.methodC_DirectCall(orderId);
        return buildResult(method, orderId);
    }

    @PostMapping("/before-commit-fail/{orderId}")
    public Map<String, Object> testBeforeCommitFail(@PathVariable Long orderId) {
        try {
            comparisonService.methodA_BeforeCommit_WithFailure(orderId);
            return Map.of("result", "should not reach here");
        } catch (Exception e) {
            return Map.of(
                    "method", "BEFORE_COMMIT_FAIL",
                    "error", e.getMessage(),
                    "outbox_pending", outboxEventRepository.countByStatus(OutboxStatus.PENDING),
                    "설명", "TX 롤백 → Outbox도 롤백 → PENDING 0건이어야 함"
            );
        }
    }

    @PostMapping("/direct-call-fail/{orderId}")
    public Map<String, Object> testDirectCallFail(@PathVariable Long orderId) {
        try {
            comparisonService.methodC_DirectCall_WithFailure(orderId);
            return Map.of("result", "should not reach here");
        } catch (Exception e) {
            return Map.of(
                    "method", "DIRECT_CALL_FAIL",
                    "error", e.getMessage(),
                    "outbox_pending", outboxEventRepository.countByStatus(OutboxStatus.PENDING),
                    "설명", "TX 롤백 → Outbox도 롤백 → PENDING 0건이어야 함"
            );
        }
    }

    private Map<String, Object> buildResult(String method, Long orderId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("method", method);
        result.put("orderId", orderId);
        result.put("outbox_pending", outboxEventRepository.countByStatus(OutboxStatus.PENDING));
        result.put("outbox_published", outboxEventRepository.countByStatus(OutboxStatus.PUBLISHED));
        result.put("outbox_total", outboxEventRepository.count());
        return result;
    }

    @GetMapping("/outbox-status")
    public Map<String, Object> getOutboxStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("PENDING", outboxEventRepository.countByStatus(OutboxStatus.PENDING));
        result.put("PUBLISHED", outboxEventRepository.countByStatus(OutboxStatus.PUBLISHED));
        result.put("FAILED", outboxEventRepository.countByStatus(OutboxStatus.FAILED));
        result.put("total", outboxEventRepository.count());
        return result;
    }
}
