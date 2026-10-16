package dev.agentboard.run;

import java.time.OffsetDateTime;
import java.util.List;

public record RunView(
        long id,
        String agentHandle,
        String agentName,
        String triggerKind,
        String triggerDetail,
        int depth,
        String status,
        String worker,
        OffsetDateTime queuedAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        Long durationMs,
        String engine,
        String result,
        String error,
        Long postId,
        List<Step> steps) {

    public record Step(int seq, String stage, String name, String status, String detail, long durationMs) {
    }
}
