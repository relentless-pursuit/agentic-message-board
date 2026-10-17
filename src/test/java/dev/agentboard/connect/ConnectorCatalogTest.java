package dev.agentboard.connect;

import dev.agentboard.config.BoardProperties;
import dev.agentboard.registry.AgentDefinition;
import dev.agentboard.run.RunContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ConnectorCatalogTest {
    private final ObjectMapper json=new ObjectMapper();
    private ConnectorCatalog catalog(String...names){
        var props=new BoardProperties();props.getHttp().setAllowedHosts(List.of("api.github.com","hacker-news.firebaseio.com","www.githubstatus.com"));
        List<Connector> connectors=Arrays.stream(names).map(name->(Connector)new Connector(){public String name(){return name;}public Observation fetch(Map<String,Object> options,RunContext context){throw new AssertionError("Catalog validation must not fetch data");}}).toList();
        return new ConnectorCatalog(new ConnectorRegistry(connectors),new SafeHttp(props,json),props);
    }
    private AgentDefinition agent(String connector,Map<String,Object> settings){
        return json.convertValue(Map.of("trigger",Map.of(),
                "observe",List.of(Map.of("connector",connector,"with",settings))),AgentDefinition.class);
    }
    @Test void catalogUsesAvailableImplementationsRatherThanRegisteredAgents(){
        assertThat(catalog("rss","github_pulls").list()).extracting(ConnectorCatalog.Entry::name).containsExactly("github_pulls","rss");
        assertThat(catalog("rss").list()).noneMatch(c->c.name().equals("github_actions"));
        assertThat(catalog("new_plugin").list().get(0).formSupported()).isFalse();
    }
    @Test void supportsCorrectInputsAndFixedSourcesWithoutAnyNetworkCalls(){
        var catalog=catalog("board","github_issues","github_pulls","github_releases","hackernews","http_check","rss");
        catalog.validate(agent("github_issues",Map.of("repo","example/repository","limit",10)));
        catalog.validate(agent("http_check",Map.of("urls",List.of("https://www.githubstatus.com"),"slow_ms",2000)));
        catalog.validate(agent("github_pulls",Map.of("repo","example/repository","limit",10)));
        catalog.validate(agent("github_releases",Map.of("repos",List.of("example/repository"))));
        catalog.validate(agent("rss",Map.of("feeds",List.of("https://www.githubstatus.com/history.atom"))));
        catalog.validate(agent("hackernews",Map.of("top",15)));
        catalog.validate(agent("board",Map.of("since","24h")));
        assertThat(catalog.list()).hasSize(7);
    }
    @Test void rejectsMissingConnectorsAndLegacyAmbiguousTargetOption(){
        var catalog=catalog("http_check");
        assertThatThrownBy(()->catalog.validate(agent("xyz",Map.of()))).hasMessageContaining("not available");
        assertThatThrownBy(()->catalog.validate(agent("http_check",Map.of("target","https://www.githubstatus.com")))).hasMessageContaining("Unknown http_check option");
        assertThatThrownBy(()->catalog.validate(agent("http_check",Map.of()))).hasMessageContaining("required");
    }
    @Test void validatesSourceSyntaxHostRulesAndNumericLimitsBeforeSaving(){
        var catalog=catalog("http_check","github_pulls","hackernews","board");
        assertThatThrownBy(()->catalog.validate(agent("http_check",Map.of("urls",List.of("https://not-allowed.example"))))).hasMessageContaining("allow-list");
        assertThatThrownBy(()->catalog.validate(agent("http_check",Map.of("urls",List.of())))).hasMessageContaining("1–20");
        assertThatThrownBy(()->catalog.validate(agent("github_pulls",Map.of("repo","https://github.com/owner/repo")))).hasMessageContaining("owner/repository");
        assertThatThrownBy(()->catalog.validate(agent("hackernews",Map.of("top",31)))).hasMessageContaining("integer");
        assertThatThrownBy(()->catalog.validate(agent("hackernews",Map.of("top",1.5)))).hasMessageContaining("integer");
        assertThatThrownBy(()->catalog.validate(agent("board",Map.of("since","yesterday")))).hasMessageContaining("24h");
    }
}
