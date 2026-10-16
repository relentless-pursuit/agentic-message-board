package dev.agentboard.act;

import dev.agentboard.connect.With;
import dev.agentboard.hook.OutboundWebhookService;
import dev.agentboard.reason.Reasoning;
import dev.agentboard.rules.Condition;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Notifies another system, but only when the rule says so. with: { target: teams, when: "severity >= high" } */
@Component
public class WebhookAction implements Action {

    private final OutboundWebhookService webhooks;

    public WebhookAction(OutboundWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public ActionResult execute(Map<String, Object> with, ActContext ctx) {
        String target = With.string(with, "target", "");
        Condition when = Condition.parse(With.string(with, "when", null));
        Reasoning r = ctx.reasoning();
        Map<String, Object> facts = Map.of("severity", r.severity(), "type", ctx.postType(), "tags", r.tags(),
                "agent", ctx.run().agent().handle());
        if (!when.test(facts)) {
            return ActionResult.skipped("rule not met (" + when.text() + "), nothing sent to " + target);
        }
        Map<String, Object> payload = OutboundWebhookService.payload(
                ctx.run().agent().handle(), ctx.run().agent().name(), ctx.postId(), ctx.postType(), r.severity(), r.title(), r.body(), r.tags(),
                webhooks.postUrl(ctx.postId()));
        payload.put("runId", ctx.run().runId());
        OutboundWebhookService.Delivery d = webhooks.send(target, payload, ctx.run().agent().handle());
        return switch (d.outcome()) {
            case "sent" -> ActionResult.ok("sent to " + target + ": " + d.detail());
            case "not-configured" -> ActionResult.skipped(d.detail());
            default -> ActionResult.failed("could not send to " + target + ": " + d.detail());
        };
    }
}
