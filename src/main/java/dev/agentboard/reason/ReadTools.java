package dev.agentboard.reason;

import dev.agentboard.run.RunContext;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * Run-scoped evidence. Every MCP connector result an agent receives becomes a citable evidence document here, next to
 * the evidence inherited from upstream agents. The former built-in read tools are retired in favour of MCP connectors.
 */
@Component
public class ReadTools {
    /** Names of the retired built-in read tools; saved skills that still list them are cleaned at start-up. */
    public static final Set<String> NAMES=Set.of("read_issue","list_files","read_file","read_releases","read_rss","read_hackernews");
    public Session session(RunContext ctx,List<Map<String,Object>> inherited){return new Session(inherited);}
    @SuppressWarnings("unchecked") static Map<String,Object> asMap(Object o){if(!(o instanceof Map<?,?>))throw new IllegalArgumentException("Unexpected source response.");return (Map<String,Object>)o;}
    static int number(Object o){if(!(o instanceof Number n)||n.doubleValue()!=n.intValue())throw new IllegalArgumentException("Expected integer source parameter.");return n.intValue();}
    public static class Session {
        public final List<Map<String,Object>> evidence=new ArrayList<>();public final List<Map<String,Object>> traces=new ArrayList<>();
        Session(List<Map<String,Object>> inherited){evidence.addAll(inherited);}
    }
    static String clip(String s,int limit){return s.length()>limit?s.substring(0,limit)+"\n[omitted: source exceeds excerpt limit]":s;}
}
