package dev.agentboard.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Fails fast on settings that cannot work together, with a message that says how to fix them. */
@Component
public class StartupChecks implements InitializingBean {

    private final BoardProperties props;

    public StartupChecks(BoardProperties props) {
        this.props = props;
    }

    @Override
    public void afterPropertiesSet() {
        if (!Set.of("all", "web", "worker").contains(props.getRole())) {
            throw new IllegalStateException("board.role must be all, web or worker (was '" + props.getRole() + "')");
        }
        if (!Set.of("memory", "database").contains(props.getQueue())) {
            throw new IllegalStateException("board.queue must be memory or database (was '" + props.getQueue() + "')");
        }
        if (!props.getRole().equals("all") && props.getQueue().equals("memory")) {
            throw new IllegalStateException("With separate web and worker processes the queue must be shared: set BOARD_QUEUE=database");
        }
        props.zone();
        if(!"openai".equals(props.getReasoningProvider())) throw new IllegalStateException("board.reasoning-provider must be openai");
        var ai=props.getOpenai();
        if(ai.getMaxCalls()<0 || ai.getBudgetUsd()<0 || ai.getMaxConcurrent()<1 || ai.getMaxConcurrent()>4)
            throw new IllegalStateException("Invalid OpenAI pilot limits");
    }
}
