package dev.agentboard.reason;

import dev.agentboard.connect.*;
import dev.agentboard.config.SecretResolver;
import dev.agentboard.run.RunContext;
import org.springframework.stereotype.Component;
import java.nio.charset.*;
import java.nio.*;
import java.net.URLEncoder;
import java.util.*;

/** Run-scoped, read-only tools. Repository and revision are fixed by the workflow, never by model arguments. */
@Component
public class ReadTools {
    public static final Set<String> NAMES=Set.of("read_issue","list_files","read_file","read_releases","read_rss","read_hackernews");
    private final SafeHttp http;private final SecretResolver secrets;private final ConnectorRegistry connectors;
    public ReadTools(SafeHttp http,SecretResolver secrets,ConnectorRegistry connectors){this.http=http;this.secrets=secrets;this.connectors=connectors;}
    /** What a person must enter before a run so each read tool has its source: tool name to parameter keys. */
    private static final Map<String,List<String>> TOOL_PARAMETERS=Map.of("read_issue",List.of("repository","issue"),"list_files",List.of("repository","revision"),
        "read_file",List.of("repository","revision"),"read_releases",List.of("repository"),"read_rss",List.of("rssUrl"),"read_hackernews",List.of("hnTop"));
    private static final List<Map<String,Object>> PARAMETER_FIELDS=List.of(
        Map.of("key","repository","label","GitHub repository","type","text","placeholder","owner/repository"),
        Map.of("key","issue","label","Issue number","type","number","min",1,"placeholder","for example 123"),
        Map.of("key","revision","label","Branch, tag or commit","type","text","placeholder","main"),
        Map.of("key","rssUrl","label","RSS or Atom feed address","type","url","placeholder","https://example.com/feed.xml"),
        Map.of("key","hnTop","label","Hacker News stories (1-10)","type","number","min",1,"max",10,"placeholder","3"));
    /** Published to the page so it can ask for exactly the parameters the chosen tools need. */
    public static Map<String,Object> parameterSpec(){return Map.of("fields",PARAMETER_FIELDS,"tools",TOOL_PARAMETERS);}
    private static final Set<String> GITHUB_TOOLS=Set.of("read_issue","list_files","read_file","read_releases");
    /** Checks and pins the run parameters. Only what the chosen tools need is required, and nothing has a built-in default. */
    public Map<String,Object> pin(Map<String,Object> raw,Collection<String> tools)throws Exception{
        var p=new LinkedHashMap<String,Object>();
        if(raw!=null)raw.forEach((k,v)->{if(v!=null&&!(v instanceof String text&&text.isBlank()))p.put(k,v);});
        if(!Set.of("repository","issue","revision","rssUrl","hnTop").containsAll(p.keySet()))throw new IllegalArgumentException("Unknown source parameter.");
        if(tools.stream().anyMatch(GITHUB_TOOLS::contains)){
            String repo=Objects.toString(p.get("repository"),"");
            if(repo.isBlank())throw new IllegalArgumentException("Enter the repository (owner/name).");
            if(!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))throw new IllegalArgumentException("Repository must be owner/name.");
            if(Arrays.stream(repo.split("/")).anyMatch(part->part.equals(".")||part.equals("..")))throw new IllegalArgumentException("Invalid repository name.");
            p.put("repository",repo);
            if(tools.contains("read_issue")){
                if(p.get("issue")==null)throw new IllegalArgumentException("Enter the issue number.");
                int issue=number(p.get("issue"));if(issue<1)throw new IllegalArgumentException("Issue must be positive.");p.put("issue",issue);
            }
            if(tools.contains("list_files")||tools.contains("read_file")){
                String rev=Objects.toString(p.get("revision"),"");
                if(rev.isBlank()||rev.length()>200)throw new IllegalArgumentException("Enter a branch, tag or commit.");
                if(!rev.matches("[a-fA-F0-9]{40}"))rev=Objects.toString(asMap(http.getJson(base(repo)+"/commits/"+enc(rev),headers())).get("sha"),"");
                if(!rev.matches("[a-fA-F0-9]{40}"))throw new IllegalArgumentException("GitHub did not resolve the revision to a commit.");
                p.put("revision",rev.toLowerCase(Locale.ROOT));
            }
        }
        if(tools.contains("read_rss")){
            String url=Objects.toString(p.get("rssUrl"),"");
            if(url.isBlank())throw new IllegalArgumentException("Enter the RSS or Atom feed address.");
            http.validateAddress(url);
        }
        if(tools.contains("read_hackernews")){
            if(p.get("hnTop")==null)throw new IllegalArgumentException("Enter how many Hacker News stories to read.");
            int top=number(p.get("hnTop"));if(top<1||top>10)throw new IllegalArgumentException("Hacker News count must be 1–10.");p.put("hnTop",top);
        }
        return p;
    }
    private Map<String,String> headers(){var h=new HashMap<String,String>();h.put("Accept","application/vnd.github+json");secrets.resolve("GITHUB_TOKEN").ifPresent(t->h.put("Authorization","Bearer "+t));return h;}
    private static String base(String repo){return "https://api.github.com/repos/"+repo;}
    private static String enc(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8).replace("+","%20");}
    @SuppressWarnings("unchecked") static Map<String,Object> asMap(Object o){if(!(o instanceof Map<?,?>))throw new IllegalArgumentException("Unexpected source response.");return (Map<String,Object>)o;}
    static int number(Object o){if(!(o instanceof Number n)||n.doubleValue()!=n.intValue())throw new IllegalArgumentException("Expected integer source parameter.");return n.intValue();}
    public Session session(RunContext ctx,List<Map<String,Object>> inherited){return new Session(ctx,inherited);}
    public static List<Map<String,Object>> schemas(Collection<String> names){
        var out=new ArrayList<Map<String,Object>>();for(String name:names){if(!NAMES.contains(name))throw new IllegalArgumentException("Unsupported tool: "+name);
            var fields=new LinkedHashMap<String,Object>();if(name.equals("read_file")){fields.put("path",Map.of("type","string"));fields.put("startLine",Map.of("type","integer","minimum",1));fields.put("endLine",Map.of("type","integer","minimum",1));}
            out.add(Map.of("type","function","name",name,"description",switch(name){case "read_issue"->"Read the selected issue body and reproduction steps.";case "list_files"->"List source paths in the pinned commit. Call before reading files.";case "read_file"->"Read up to 200 numbered lines from an existing pinned source path.";case "read_releases"->"Read up to three recent release descriptions.";case "read_rss"->"Read recent items from the selected RSS feed.";default->"Read the selected number of Hacker News stories.";},"strict",true,"parameters",Map.of("type","object","properties",fields,"required",List.copyOf(fields.keySet()),"additionalProperties",false)));
        }return out;
    }
    public class Session {
        public final List<Map<String,Object>> evidence=new ArrayList<>();public final List<Map<String,Object>> traces=new ArrayList<>();
        private final RunContext ctx;private final Map<String,Object> params;private Set<String> paths;private int sequence;
        @SuppressWarnings("unchecked") Session(RunContext ctx,List<Map<String,Object>> inherited){this.ctx=ctx;params=(Map<String,Object>)ctx.event().getOrDefault("parameters",Map.of());evidence.addAll(inherited);}
        public Object call(String name,Map<String,Object> args)throws Exception{
            if(!NAMES.contains(name))throw new IllegalArgumentException("Unknown tool.");
            String repo=Objects.toString(params.get("repository"),""),sha=Objects.toString(params.get("revision"),"");
            if(name.startsWith("read_")&&!List.of("read_rss","read_hackernews").contains(name)||name.equals("list_files")){
                if(!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")||!sha.matches("[a-f0-9]{40}"))throw new IllegalArgumentException("Set repository, issue and pinned revision for GitHub tools.");
            }
            Map<String,Object> result=new LinkedHashMap<>();String url="",path="";int first=0,last=0;
            switch(name){
                case "read_issue"->{int issue=number(params.get("issue"));url="https://github.com/"+repo+"/issues/"+issue;var issueData=asMap(http.getJson(base(repo)+"/issues/"+issue,headers()));if(issueData.containsKey("pull_request"))throw new IllegalArgumentException("Select an issue rather than a pull request.");result.put("title",issueData.getOrDefault("title",""));result.put("body",clip(Objects.toString(issueData.get("body"),""),12000));result.put("state",issueData.getOrDefault("state","unknown"));}
                case "list_files"->{loadTree(repo,sha);result.put("paths",paths.stream().sorted().limit(300).toList());result.put("omitted",Math.max(0,paths.size()-300));}
                case "read_file"->{path=Objects.toString(args.get("path"),"");if(path.startsWith("/")||Arrays.asList(path.split("/")).contains(".."))throw new IllegalArgumentException("Invalid source path.");loadTree(repo,sha);if(!paths.contains(path))throw new IllegalArgumentException("Path is absent from the pinned tree.");
                    var file=asMap(http.getJson(base(repo)+"/contents/"+Arrays.stream(path.split("/")).map(ReadTools::enc).collect(java.util.stream.Collectors.joining("/"))+"?ref="+sha,headers()));
                    if(!"file".equals(file.get("type"))||!"base64".equals(file.get("encoding")))throw new IllegalArgumentException("Only regular text files can be read.");
                    if(((Number)file.getOrDefault("size",0)).longValue()>200000)throw new IllegalArgumentException("File exceeds the 200 KB inspection limit.");
                    byte[] bytes=Base64.getMimeDecoder().decode(Objects.toString(file.get("content"),""));if(bytes.length>200000)throw new IllegalArgumentException("File exceeds the 200 KB inspection limit.");String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();if(text.indexOf('\0')>=0)throw new IllegalArgumentException("Binary files are excluded.");
                    String[] lines=text.split("\\R",-1);first=number(args.get("startLine"));int requestedEnd=number(args.get("endLine"));if(first<1||first>lines.length||requestedEnd<first)throw new IllegalArgumentException("Invalid source line range.");last=Math.min(Math.min(requestedEnd,first+199),lines.length);var numbered=new StringBuilder();int actual=first-1;for(int i=first;i<=last;i++){String line=i+": "+lines[i-1]+"\n";if(numbered.length()+line.length()>12000)break;numbered.append(line);actual=i;}last=actual;if(last<first)throw new IllegalArgumentException("Source line exceeds the excerpt limit.");
                    result.put("text",numbered.toString());result.put("omitted",last<requestedEnd);url="https://github.com/"+repo+"/blob/"+sha+"/"+path+"#L"+first+"-L"+last;}
                case "read_releases"->{Object data=http.getJson(base(repo)+"/releases?per_page=3",headers());result.put("releases",data instanceof List<?> list?list.stream().limit(3).map(o->{var m=asMap(o);return Map.of("tag",Objects.toString(m.get("tag_name"),""),"body",clip(Objects.toString(m.get("body"),""),2500),"url",Objects.toString(m.get("html_url"),""));}).toList():List.of());url="https://github.com/"+repo+"/releases";}
                default->{String connector=name.equals("read_rss")?"rss":"hackernews";Map<String,Object> settings=connector.equals("rss")?Map.of("feeds",List.of(Objects.toString(params.get("rssUrl"),"")),"hours",168):Map.of("top",Math.min(10,Math.max(1,number(params.get("hnTop")))));var obs=connectors.get(connector).orElseThrow().fetch(settings,ctx);result.put("summary",obs.summary());result.put("facts",obs.facts().stream().limit(10).toList());result.put("omitted",Math.max(0,obs.facts().size()-10));url=connector.equals("rss")?Objects.toString(params.get("rssUrl"),""):"https://news.ycombinator.com/";}
            }
            String id="run"+ctx.runId()+"-e"+(++sequence);var doc=new LinkedHashMap<String,Object>();doc.put("id",id);doc.put("tool",name);doc.put("url",url);doc.put("path",path);doc.put("startLine",first);doc.put("endLine",last);doc.put("revision",sha);doc.put("content",result);evidence.add(doc);return doc;
        }
        private void loadTree(String repo,String sha)throws Exception{if(paths!=null)return;var tree=asMap(http.getJson(base(repo)+"/git/trees/"+sha+"?recursive=1",headers()));if(Boolean.TRUE.equals(tree.get("truncated")))throw new IllegalArgumentException("Repository tree is incomplete; narrow the source or choose a smaller repository.");paths=new HashSet<>();for(Object item:(List<?>)tree.getOrDefault("tree",List.of())){var e=asMap(item);if("blob".equals(e.get("type")))paths.add(Objects.toString(e.get("path")));}}
    }
    static String clip(String s,int limit){return s.length()>limit?s.substring(0,limit)+"\n[omitted: source exceeds excerpt limit]":s;}
}
