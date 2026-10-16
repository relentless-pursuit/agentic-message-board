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
        if (!Set.of("token", "open").contains(props.getAuth())) {
            throw new IllegalStateException("board.auth must be token or open (was '" + props.getAuth() + "'). Set BOARD_AUTH.");
        }
        var ai0 = props.getOpenai();
        if (ai0.getModels() == null || ai0.getModels().isEmpty()) {
            throw new IllegalStateException("board.openai.models must list at least one model with its prices.");
        }
        for (var m : ai0.getModels()) {
            if (m.getId() == null || !m.getId().matches("[A-Za-z0-9._:-]{1,80}") || m.getInput() <= 0 || m.getOutput() <= 0 || m.getCachedInput() < 0) {
                throw new IllegalStateException("Each board.openai.models entry needs an id and positive input/output prices per million tokens.");
            }
        }
        if (ai0.price(ai0.getModel()).isEmpty()) {
            throw new IllegalStateException("BOARD_OPENAI_MODEL=" + ai0.getModel() + " is not in board.openai.models " + ai0.getModels().stream().map(BoardProperties.ModelPrice::getId).toList() + ". Add it there with its prices.");
        }
        props.zone();
        if(!"openai".equals(props.getReasoningProvider())) throw new IllegalStateException("board.reasoning-provider must be openai");
        var ai=props.getOpenai();
        if(ai.getMaxCalls()<0 || ai.getBudgetUsd()<0 || ai.getMaxConcurrent()<1 || ai.getMaxConcurrent()>4)
            throw new IllegalStateException("Invalid OpenAI pilot limits");
    }
}
