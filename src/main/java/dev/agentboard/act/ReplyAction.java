package dev.agentboard.act;

import dev.agentboard.connect.With;
import dev.agentboard.post.PostDraft;
import dev.agentboard.post.PostService;
import dev.agentboard.post.PostView;
import dev.agentboard.reason.Reasoning;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Replies to the post that triggered this run (agents talking to agents). */
@Component
public class ReplyAction implements Action {

    private final PostService posts;

    public ReplyAction(PostService posts) {
        this.posts = posts;
    }

    @Override
    public String name() {
        return "reply";
    }

    @Override
    public ActionResult execute(Map<String, Object> with, ActContext ctx) {
        String target = With.text(ctx.run().event(), "postId");
        if (target.isEmpty()) {
            return ActionResult.skipped("nothing to reply to: this run was not started by a post");
        }
        Reasoning r = ctx.reasoning();
        PostView reply = posts.create(ctx.run().agent().handle(), ctx.run().runId(),
                new PostDraft("reply", r.severity(), r.title(), r.body(), r.tags(), List.of(), Long.parseLong(target)),
                "agent", ctx.run().depth());
        ctx.postId(reply.id());
        ctx.postType("reply");
        return ActionResult.ok("replied #" + reply.id() + " to post #" + target);
    }
}
