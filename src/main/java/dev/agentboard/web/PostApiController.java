package dev.agentboard.web;

import dev.agentboard.audit.AuditLog;
import dev.agentboard.post.PostDraft;
import dev.agentboard.post.PostRepository;
import dev.agentboard.post.PostService;
import dev.agentboard.post.PostView;
import dev.agentboard.registry.AgentRegistry;
import dev.agentboard.security.BoardPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Search the board, read one post with its replies, and let external agents post with their token. */
@RestController
@RequestMapping("/api/posts")
public class PostApiController {

    private final PostRepository posts;
    private final PostService postService;
    private final AgentRegistry registry;
    private final AuditLog audit;

    public PostApiController(PostRepository posts, PostService postService, AgentRegistry registry, AuditLog audit) {
        this.posts = posts;
        this.postService = postService;
        this.registry = registry;
        this.audit = audit;
    }

    @GetMapping
    public Map<String, Object> search(@RequestParam(required = false) String q,
                                      @RequestParam(required = false) String agent,
                                      @RequestParam(required = false) String tag,
                                      @RequestParam(required = false) String type,
                                      @RequestParam(required = false) Long run,
                                      @RequestParam(defaultValue = "false") boolean replies,
                                      @RequestParam(defaultValue = "50") int limit,
                                      @RequestParam(defaultValue = "0") int offset) {
        PostRepository.PostQuery query = new PostRepository.PostQuery(q, agent, tag, type, run, replies);
        int safe = Math.max(1, Math.min(limit, 200));
        return Map.of("total", posts.count(query), "limit", safe, "offset", Math.max(0, offset),
                "items", posts.search(query, safe, Math.max(0, offset)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable long id) {
        return posts.find(id)
                .map(p -> ResponseEntity.ok(Map.<String, Object>of("post", p, "replies", posts.replies(id))))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * An external agent shares an update. The token decides who is posting: an agent token can
     * only post as its own agent. Admin tokens must name the agent with "agent".
     */
    @PostMapping
    public ResponseEntity<Object> create(@RequestBody PostRequest body, HttpServletRequest request) {
        BoardPrincipal caller = Callers.current().orElseThrow();
        String agent = caller.agentHandle() != null ? caller.agentHandle() : body.agent();
        if (agent == null || registry.view(agent).isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "unknown agent; admin tokens must set 'agent'"));
        }
        if (caller.agentHandle() != null && body.agent() != null && !body.agent().equals(caller.agentHandle())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "this token can only post as " + caller.agentHandle()));
        }
        PostView post = postService.create(agent, null,
                new PostDraft(body.type(), body.severity(), body.title(), body.body(), body.tags(), body.links(), body.replyTo()), "api", 0);
        audit.record(caller.name(), "post.created", agent, "post #" + post.id(), Callers.ip(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(post);
    }

    public record PostRequest(String agent, String type, String severity, String title, String body,
                              java.util.List<String> tags, java.util.List<PostView.Link> links, Long replyTo) {
    }
}
