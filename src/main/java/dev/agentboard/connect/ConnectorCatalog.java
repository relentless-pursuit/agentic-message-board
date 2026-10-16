package dev.agentboard.connect;

import dev.agentboard.config.BoardProperties;
import dev.agentboard.registry.AgentDefinition;
import dev.agentboard.schedule.Schedule;
import org.springframework.stereotype.Component;
import java.util.*;

/** Form schemas are offered only for connector implementations present in the runtime registry. */
@Component
public class ConnectorCatalog {
    private final ConnectorRegistry registry;
    private final SafeHttp http;
    private final BoardProperties props;
    private static final String REPO="[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+";
    public ConnectorCatalog(ConnectorRegistry registry,SafeHttp http,BoardProperties props){this.registry=registry;this.http=http;this.props=props;}
    public List<Entry> list(){return registry.names().stream().map(this::describe).toList();}
    public Map<String,Object> options(){return Map.of("connectors",list(),"allowedHosts",props.getHttp().getAllowedHosts(),"schedulerEnabled",props.getScheduler().isEnabled());}
    private Entry describe(String name){return switch(name){
        case "github_issues" -> entry(name,"List GitHub issues","List open issues and their links. Pull requests are excluded. Private repositories need a GitHub connection on the server.",
                field("repo","Repository","repo",true,"owner/repository","Enter owner/repository or paste its GitHub URL.","",0,0),number("limit","Maximum issues",10,25));
        case "http_check" -> entry(name,"HTTP health check","Read status and response time from HTTPS addresses.",
                field("urls","Website / endpoint URLs","url-list",true,"https://www.githubstatus.com","One HTTPS URL per line. The host must be on this board's allow-list.","",0,0),
                number("slow_ms","Slow-response threshold (ms)",2000,60000));
        case "github_pulls" -> entry(name,"List GitHub pull requests","Read open pull requests. Private repositories need a GitHub connection on the server.",
                field("repo","Repository","repo",true,"organisation/repository","Use owner/repository, not a GitHub page URL.","",0,0),number("limit","Maximum pull requests",10,30));
        case "github_releases" -> entry(name,"List GitHub releases","Read recent releases. Private repositories need a GitHub connection on the server.",
                field("repos","Repositories","repo-list",true,"organisation/repository","One owner/repository per line.","",0,0),number("days","Look back (days)",14,365));
        case "rss" -> entry(name,"Read RSS / Atom updates","Read recent entries from HTTPS feed addresses.",
                field("feeds","Feed URLs","url-list",true,"https://www.githubstatus.com/history.atom","One HTTPS RSS or Atom URL per line. The host must be allowed.","",0,0),number("hours","Look back (hours)",24,720));
        case "hackernews" -> entry(name,"List Hacker News stories","Source is fixed: Hacker News top stories. No source URL is required.",number("top","Number of stories",15,30));
        case "board" -> entry(name,"Agent Board posts","Source is this board's recent posts. No external URL is required.",
                field("since","Look-back period","duration",true,"24h","For example 30m, 24h or 7d.","24h",0,0));
        case "workflow_input" -> entry(name,"Workflow input","Reads this workflow run's source and completed upstream results. No external connection.");
        case "event" -> new Entry(name,"React to a board event","Choose the board event to read. No source URL is required.",true,List.of(),List.of("post.created","agent.registered"));
        default -> new Entry(name,name,"This connector has no registration form schema. Configure it through YAML.",false,List.of(),List.of());
    };}
    private static Entry entry(String name,String label,String description,Field...fields){return new Entry(name,label,description,true,List.of(fields),List.of());}
    private static Field field(String key,String label,String type,boolean required,String placeholder,String help,String defaultValue,int min,int max){return new Field(key,label,type,required,placeholder,help,defaultValue,min,max);}
    private static Field number(String key,String label,int value,int max){return field(key,label,"number",false,"","",String.valueOf(value),1,max);}
    public void validate(AgentDefinition agent){
        if(agent.observe()==null)return;
        for(var step:agent.observe()){
            if(registry.get(step.connector()).isEmpty())throw new IllegalArgumentException("Connector '"+step.connector()+"' is not available. Refresh the connector choices.");
            Entry entry=describe(step.connector());
            if(!entry.formSupported())throw new IllegalArgumentException("This connector must be configured through YAML.");
            Map<String,Object> settings=step.with()==null?Map.of():step.with();
            Set<String> keys=new HashSet<>();entry.fields().forEach(f->keys.add(f.key()));
            for(String key:settings.keySet())if(!keys.contains(key))throw new IllegalArgumentException("Unknown "+step.connector()+" option '"+key+"'. Use the fields offered by this connector.");
            for(Field field:entry.fields()){
                Object value=settings.get(field.key());
                if(value==null){if(field.required() && field.defaultValue().isBlank())throw new IllegalArgumentException(field.label()+" is required.");continue;}
                String text=value.toString().trim();
                switch(field.type()){
                    case "number" -> {
                        if(!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue()!=n.intValue() || n.intValue()<field.min() || n.intValue()>field.max())
                            throw new IllegalArgumentException(field.label()+" must be an integer from "+field.min()+" to "+field.max()+".");
                    }
                    case "repo" -> {if(!(value instanceof String) || text.length()>200 || !text.matches(REPO))throw new IllegalArgumentException("Repository must look like owner/repository.");}
                    case "repo-list","url-list" -> {
                        List<?> values=value instanceof List<?> list?list:value instanceof String?List.of(value):List.of();
                        if(values.isEmpty() || values.size()>20)throw new IllegalArgumentException(field.label()+" needs 1–20 entries.");
                        for(Object item:values){
                            if(!(item instanceof String s) || s.isBlank())throw new IllegalArgumentException(field.label()+" must contain non-empty text entries.");
                            if(field.type().equals("repo-list")){if(!s.trim().matches(REPO))throw new IllegalArgumentException("Repository must look like owner/repository.");}
                            else http.checked(s);
                        }
                    }
                    case "duration" -> {if(!(value instanceof String) || !text.matches("[1-9][0-9]*[smhd]"))throw new IllegalArgumentException(field.label()+" must look like 30m, 24h or 7d.");Schedule.parseEvery(text);}
                    default -> throw new IllegalArgumentException("Unsupported connector field.");
                }
            }
            if(step.connector().startsWith("github_"))http.checked("https://api.github.com/");
            if(step.connector().equals("hackernews"))http.checked("https://hacker-news.firebaseio.com/");
            // Signed hooks remain configurable through the existing API/YAML secret settings.
            // The simple form offers events that need no separate inbound-secret setup.
            if(step.connector().equals("event") && (agent.trigger().onEvent()==null ||
                    (!entry.events().contains(agent.trigger().onEvent()) && !"hook.received".equals(agent.trigger().onEvent()))))
                throw new IllegalArgumentException("Choose a supported board event for the event connector.");
        }
    }
    public record Entry(String name,String label,String description,boolean formSupported,List<Field> fields,List<String> events){}
    public record Field(String key,String label,String type,boolean required,String placeholder,String help,String defaultValue,int min,int max){}
}
