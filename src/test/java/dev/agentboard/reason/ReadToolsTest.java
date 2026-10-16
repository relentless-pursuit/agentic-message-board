package dev.agentboard.reason;
import dev.agentboard.connect.*;
import dev.agentboard.config.SecretResolver;
import dev.agentboard.registry.AgentDefinition;
import dev.agentboard.run.RunContext;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ReadToolsTest {
 final SafeHttp http=mock(SafeHttp.class);final SecretResolver secrets=mock(SecretResolver.class);final ConnectorRegistry connectors=mock(ConnectorRegistry.class);
 final ReadTools tools=new ReadTools(http,secrets,connectors);final String sha="a".repeat(40),base="https://api.github.com/repos/test/repo";
 RunContext ctx(){var agent=new AgentDefinition("a","A","Review","team",null,"company",List.of(),null,List.of(),"Review",List.of(),true);return new RunContext(7,agent,"workflow","fixture",0,Map.of("parameters",Map.of("repository","test/repo","issue",1,"revision",sha)));}
 @BeforeEach void init()throws Exception{when(secrets.resolve("GITHUB_TOKEN")).thenReturn(Optional.empty());when(http.getJson(eq(base+"/git/trees/"+sha+"?recursive=1"),anyMap())).thenReturn(Map.of("truncated",false,"tree",List.of(Map.of("type","blob","path","src/code.py"))));}
 void file(String text)throws Exception{when(http.getJson(eq(base+"/contents/src/code.py?ref="+sha),anyMap())).thenReturn(Map.of("type","file","encoding","base64","size",text.length(),"content",Base64.getEncoder().encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))));}
 @Test void pinsBranchToCommitAndRejectsForeignRepositorySyntax()throws Exception{when(http.getJson(eq(base+"/commits/main"),anyMap())).thenReturn(Map.of("sha",sha));assertThat(tools.pin(Map.of("repository","test/repo","revision","main")).get("revision")).isEqualTo(sha);assertThatThrownBy(()->tools.pin(Map.of("repository","https://evil.test/repo"))).hasMessageContaining("owner/name");}
 @Test void fileReadsStayInsidePinnedTreeAndRetainNumberedRanges()throws Exception{file("one\ntwo\nthree");var session=tools.session(ctx(),List.of());var result=ReadTools.asMap(session.call("read_file",Map.of("path","src/code.py","startLine",2,"endLine",3)));assertThat(result).containsEntry("startLine",2).containsEntry("endLine",3);assertThat(result.get("url")).isEqualTo("https://github.com/test/repo/blob/"+sha+"/src/code.py#L2-L3");assertThat(result.get("content").toString()).contains("2: two","3: three");assertThatThrownBy(()->session.call("read_file",Map.of("path","../secret","startLine",1,"endLine",2))).hasMessageContaining("Invalid source path");assertThatThrownBy(()->session.call("read_file",Map.of("path","absent.py","startLine",1,"endLine",2))).hasMessageContaining("absent");}
 @Test void incompleteTreeAndBinaryContentFailExplicitly()throws Exception{when(http.getJson(eq(base+"/git/trees/"+sha+"?recursive=1"),anyMap())).thenReturn(Map.of("truncated",true));assertThatThrownBy(()->tools.session(ctx(),List.of()).call("list_files",Map.of())).hasMessageContaining("incomplete");init();file("nul\0binary");assertThatThrownBy(()->tools.session(ctx(),List.of()).call("read_file",Map.of("path","src/code.py","startLine",1,"endLine",2))).hasMessageContaining("Binary");}
 @Test void excerptsExposeOmittedLines()throws Exception{file("line\n".repeat(300));var r=ReadTools.asMap(tools.session(ctx(),List.of()).call("read_file",Map.of("path","src/code.py","startLine",1,"endLine",300)));assertThat(r).containsEntry("endLine",200);assertThat(ReadTools.asMap(r.get("content"))).containsEntry("omitted",true);}
 @Test void citationsMustReferenceRetrievedEvidenceAndRanges(){var evidence=Map.<String,Object>of("id","e1","path","src/code.py","startLine",2,"endLine",5,"url","https://github.com/test/repo/blob/"+sha+"/src/code.py#L2-L5");var answer=new LinkedHashMap<String,Object>(Map.of("title","Report","summary","Finding","findings",List.of(Map.of("text","Effect","kind","hypothesis","evidenceIds",List.of("e1"))),"uncertainties",List.of("Potential impact"),"citations",List.of(new LinkedHashMap<>(Map.of("evidenceId","e1","startLine",2,"endLine",4)))));AgentModelRuntime.validate(answer,List.of(evidence));assertThat(answer.get("citations").toString()).contains("#L2-L4");answer.put("citations",List.of(Map.of("evidenceId","e1","startLine",1,"endLine",4)));assertThatThrownBy(()->AgentModelRuntime.validate(answer,List.of(evidence))).hasMessageContaining("outside");answer.put("citations",List.of(Map.of("evidenceId","invented","startLine",2,"endLine",4)));assertThatThrownBy(()->AgentModelRuntime.validate(answer,List.of(evidence))).hasMessageContaining("not retrieved");}
 @Test void urlCitationsUseCanonicalUrlRatherThanInventedLineCoordinates(){
  var citation=new LinkedHashMap<String,Object>(Map.of("evidenceId","issue","startLine",1,"endLine",10));var answer=new LinkedHashMap<String,Object>(Map.of("title","Issue","summary","Summary","findings",List.of(Map.of("text","Reported behavior","kind","observed","evidenceIds",List.of("issue"))),"uncertainties",List.of(),"citations",List.of(citation)));
  AgentModelRuntime.validate(answer,List.of(Map.of("id","issue","path","","startLine",0,"endLine",0,"url","https://github.com/test/repo/issues/1")));
  assertThat(citation).containsEntry("startLine",0).containsEntry("endLine",0).containsEntry("lineCoordinatesNormalized",true).containsEntry("url","https://github.com/test/repo/issues/1");
 }
 @Test void generatedSchemaRestrictsEvidenceIdsAndFileRanges(){
  String schema=new tools.jackson.databind.ObjectMapper().writeValueAsString(AgentModelRuntime.schema(List.of(Map.of("id","source1","startLine",10,"endLine",20,"path","src/code.py"))));
  assertThat(schema).contains("\"enum\":[\"source1\"]","\"minimum\":10","\"maximum\":20");
 }
}
