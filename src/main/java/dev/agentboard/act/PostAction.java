package dev.agentboard.act;

import dev.agentboard.connect.With;
import dev.agentboard.post.PostDraft;
import dev.agentboard.post.PostService;
import dev.agentboard.post.PostView;
import dev.agentboard.reason.Reasoning;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shares the update on the board. with: { type: digest } optionally overrides the post type. */
@Component
public class PostAction implements Action {

    private final PostService posts;

    public PostAction(PostService posts) {
        this.posts = posts;
    }

    @Override
    public String name() {
        return "post";
    }

    @Override
    public ActionResult execute(Map<String, Object> with, ActContext ctx) {
        Reasoning r = ctx.reasoning();
        String type = With.string(with, "type", ctx.postType());
        List<String> tags = new ArrayList<>(r.tags());
        if(ctx.run().triggerKind().equals("workflow") && !tags.contains(r.engine()))tags.add(r.engine());
        PostView post = posts.create(ctx.run().agent().handle(), ctx.run().runId(),
                new PostDraft(type, r.severity(), r.title(), r.body(), tags, List.of(), null), "agent", ctx.run().depth());
        ctx.postId(post.id());
        ctx.postType(post.type());
        return ActionResult.ok("posted #" + post.id() + " (" + post.type() + ", " + post.severity() + ")");
    }
}
