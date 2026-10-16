package dev.agentboard.connect.connectors;

import dev.agentboard.config.SecretResolver;
import dev.agentboard.connect.SafeHttp;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GithubIssuesConnectorTest {
    private final SafeHttp http=mock(SafeHttp.class);
    private GithubIssuesConnector connector(){var secrets=mock(SecretResolver.class);when(secrets.resolve("GITHUB_TOKEN")).thenReturn(Optional.empty());return new GithubIssuesConnector(http,secrets);}
    private Map<String,Object> issue(int number,String title){return Map.of("number",number,"title",title,"updated_at","2026-10-16T00:00:00Z");}
    @Test void listsIssuesAndExcludesPullRequests() throws Exception {
        when(http.getJson(anyString(),anyMap())).thenReturn(List.of(issue(1,"Fix checkout"),Map.of("number",2,"pull_request",Map.of("url","not-an-issue"))));
        var result=connector().fetch(Map.of("repo","example/repo"),null);
        assertThat(result.facts()).containsExactly("#1 Fix checkout — https://github.com/example/repo/issues/1");
        assertThat(result.summary()).contains("1 open issue");verify(http).getJson(contains("state=open"),anyMap());
    }
    @Test void pagesPastPullRequestsAndHonoursTheIssueLimit() throws Exception {
        List<Map<String,Object>> first=new ArrayList<>();for(int i=0;i<100;i++)first.add(Map.of("number",i,"pull_request",Map.of()));
        when(http.getJson(contains("page=1"),anyMap())).thenReturn(first);
        when(http.getJson(contains("page=2"),anyMap())).thenReturn(List.of(issue(101,"First issue"),issue(102,"Second issue")));
        var result=connector().fetch(Map.of("repo","example/repo","limit",1),null);
        assertThat(result.facts()).hasSize(1);assertThat(result.facts().get(0)).contains("#101");assertThat(result.summary()).contains("limit 1");verify(http,times(2)).getJson(anyString(),anyMap());
    }
    @Test void emptyResultsAreExplicitAndInvalidReposNeverFetch() throws Exception {
        when(http.getJson(anyString(),anyMap())).thenReturn(List.of());
        var result=connector().fetch(Map.of("repo","example/repo"),null);assertThat(result.newsworthy()).isTrue();assertThat(result.facts().get(0)).contains("No open issues");
        clearInvocations(http);assertThatThrownBy(()->connector().fetch(Map.of("repo","../../etc"),null)).hasMessageContaining("owner/repository");verifyNoInteractions(http);
    }
    @Test void permissionFailuresAreNotReportedAsAnEmptyIssueList() throws Exception {
        when(http.getJson(anyString(),anyMap())).thenThrow(new java.io.IOException("GitHub answered HTTP 403"));
        assertThatThrownBy(()->connector().fetch(Map.of("repo","example/private"),null)).hasMessageContaining("403");
    }
}
