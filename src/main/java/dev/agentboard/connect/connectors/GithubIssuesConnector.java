package dev.agentboard.connect.connectors;

import dev.agentboard.config.SecretResolver;
import dev.agentboard.connect.*;
import dev.agentboard.run.RunContext;
import org.springframework.stereotype.Component;
import java.util.*;

/** Lists open repository issues. GitHub also returns PRs here; those are explicitly excluded. */
@Component
public class GithubIssuesConnector implements Connector {
    private final SafeHttp http;
    private final SecretResolver secrets;
    public GithubIssuesConnector(SafeHttp http,SecretResolver secrets){this.http=http;this.secrets=secrets;}
    public String name(){return "github_issues";}
    public Observation fetch(Map<String,Object> settings,RunContext context) throws Exception {
        String repo=With.string(settings,"repo","").trim();
        if(repo.length()>200 || !repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))throw new IllegalArgumentException("GitHub issues needs a repository such as owner/repository.");
        int limit=With.integer(settings,"limit",10,25);List<String> facts=new ArrayList<>();Set<String> seen=new HashSet<>();
        StringBuilder key=new StringBuilder(repo+":open\n");boolean scanLimit=false;
        var headers=GithubReleasesConnector.GithubApi.headers(secrets);headers.put("X-GitHub-Api-Version","2026-03-10");
        for(int page=1;page<=5;page++){
            Object response=http.getJson("https://api.github.com/repos/"+repo+"/issues?state=open&sort=updated&direction=desc&per_page=100&page="+page,
                    headers);
            if(!(response instanceof List<?> rows))throw new IllegalStateException("GitHub returned an unexpected issue response.");
            for(Object row:rows){
                Map<String,Object> issue=With.map(row);if(issue.containsKey("pull_request"))continue;
                String number=With.text(issue,"number");if(!number.matches("[0-9]+") || !seen.add(number))continue;
                facts.add("#"+number+" "+With.oneLine(With.text(issue,"title"),100)+" — https://github.com/"+repo+"/issues/"+number);
                key.append(number).append('@').append(With.text(issue,"updated_at")).append(':').append(With.text(issue,"title")).append('\n');
                if(facts.size()>=limit)break;
            }
            if(facts.size()>=limit || rows.size()<100)break;
            if(page==5)scanLimit=true;
        }
        String summary=facts.isEmpty()?"No open issues found in "+repo:"Showing "+facts.size()+" open issue"+(facts.size()==1?"":"s")+" in "+repo;
        if(facts.size()==limit)summary+=" (limit "+limit+")";
        if(scanLimit)summary="Issue scan reached its page limit in "+repo;
        if(facts.isEmpty())facts.add(scanLimit?"No issues found among the first 500 open items; pull requests were excluded. View https://github.com/"+repo+"/issues":"No open issues found. View https://github.com/"+repo+"/issues");
        return new Observation(summary,facts,key.append(scanLimit?"scan-limit":"complete").toString(),"low",true);
    }
}
