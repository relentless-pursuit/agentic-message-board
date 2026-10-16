package dev.agentboard.schedule;

import dev.agentboard.config.BoardProperties;
import dev.agentboard.hook.HookDeliveries;
import dev.agentboard.registry.AgentDefinition;
import dev.agentboard.registry.AgentRegistry;
import dev.agentboard.registry.AgentRepository;
import dev.agentboard.run.DbLock;
import dev.agentboard.run.RunRepository;
import dev.agentboard.run.RunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

/**
 * Diagram box "Scheduler": every few seconds, asks the database which agents are due and puts
 * one job per due agent on the queue. It only plans; workers do the work.
 * Only one copy acts at a time (database lease). Trigger keys prevent duplicate scheduled runs.
 * Missed times are skipped, not replayed.
 * Screenshot source; excluded pending repository APIs, database schema and task-scheduler wiring.
 */
@Component
@ConditionalOnExpression("'${board.role:all}' != 'web' and ${board.scheduler.enabled:true}")
public class Scheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

    private final AgentRepository agents;
    private final AgentRegistry registry;
    private final RunRepository runs;
    private final RunService runService;
    private final DbLock lock;
    private final TaskScheduler taskScheduler;
    private final BoardProperties props;
    private final HookDeliveries hookDeliveries;
    private ScheduledFuture<?> task;

    public Scheduler(AgentRepository agents, AgentRegistry registry, RunRepository runs, RunService runService,
                     DbLock lock, TaskScheduler taskScheduler, BoardProperties props, HookDeliveries hookDeliveries) {
        this.agents = agents;
        this.registry = registry;
        this.runs = runs;
        this.runService = runService;
        this.lock = lock;
        this.taskScheduler = taskScheduler;
        this.props = props;
        this.hookDeliveries = hookDeliveries;
    }

    @Override
    public void start() {
        task = taskScheduler.scheduleWithFixedDelay(this::tickSafely, props.getScheduler().getTick());
    }

    private void tickSafely() {
        try {
            tick(Instant.now());
        } catch (RuntimeException e) {
            log.error("Scheduler tick failed; will try again", e);
        }
    }

    public void tick(Instant now) {
        Duration lease = props.getScheduler().getTick().multipliedBy(6).plusSeconds(10);
        if (!lock.tryAcquire("scheduler", lease)) {
            return;
        }
        failStaleRuns();
        hookDeliveries.forgetOlderThan(Duration.ofDays(1));
        for (AgentRepository.DueAgent due : agents.findDue(now, props.getScheduler().getBatchSize())) {
            AgentDefinition agent = registry.parse(due.definitionJson());
            AgentDefinition.Trigger trigger = agent.trigger();
            if (!Schedule.isTimed(trigger)) {
                continue;
            }
            boolean tooLate = trigger.every() == null
                    && Duration.between(due.scheduledFor(), now).compareTo(props.getScheduler().getGrace()) > 0;
            if (tooLate) {
                log.info("Skipping missed run of {} scheduled for {} (older than the grace period)", agent.handle(), due.scheduledFor());
            } else {
                RunService.TriggerResult r = runService.trigger(agent.handle(), "schedule", "schedule:" + due.scheduledFor(),
                        trigger.describe(), 0, null);
                if (r.outcome().equals("busy")) {
                    log.info("{} is still busy with run #{}; skipping this scheduled run", agent.handle(), r.runId());
                }
            }
            agents.setNextRun(agent.handle(), Schedule.nextAfter(agent.handle(), trigger, now, props.zone()));
        }
    }

    /** A run stuck in running means its worker died. Mark it failed so the agent is not blocked forever. */
    private void failStaleRuns() {
        for (RunRepository.RunRecord stale : runs.staleRunning(props.getScheduler().getStaleRunAfter())) {
            runs.addStep(stale.id(), 999, "error", "worker", "failed", "no result after " + props.getScheduler().getStaleRunAfter() + "; the worker probably stopped", 0);
            runs.finish(stale.id(), "failed", null, "worker stopped responding", null, null, null);
            agents.recordRunStatus(stale.agentHandle(), stale.id(), "failed", Instant.now());
        }
    }

    @Override
    public void stop() {
        if (task != null) task.cancel(false);
    }

    @Override
    public boolean isRunning() {
        return task != null && !task.isCancelled();
    }
}
