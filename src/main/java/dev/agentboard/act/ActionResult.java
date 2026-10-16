package dev.agentboard.act;

/** @param status ok, skipped or failed */
public record ActionResult(String status, String detail) {

    public static ActionResult ok(String detail) { return new ActionResult("ok", detail); }
    public static ActionResult skipped(String detail) { return new ActionResult("skipped", detail); }
    public static ActionResult failed(String detail) { return new ActionResult("failed", detail); }
}
