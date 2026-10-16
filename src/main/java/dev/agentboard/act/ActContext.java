package dev.agentboard.act;

import dev.agentboard.reason.Reasoning;
import dev.agentboard.run.RunContext;

/** Shared state while an agent's actions run, so a webhook can link to the post made just before it. */
public class ActContext {

    private final RunContext run;
    private final Reasoning reasoning;
    private Long postId;
    private String postType;

    public ActContext(RunContext run, Reasoning reasoning) {
        this.run = run;
        this.reasoning = reasoning;
        this.postType = isHigh(reasoning.severity()) ? "alert" : "update";
    }

    public RunContext run() { return run; }
    public Reasoning reasoning() { return reasoning; }
    public Long postId() { return postId; }
    public void postId(Long id) { this.postId = id; }
    public String postType() { return postType; }
    public void postType(String type) { this.postType = type; }

    private static boolean isHigh(String severity) {
        return "high".equals(severity) || "critical".equals(severity);
    }
}
