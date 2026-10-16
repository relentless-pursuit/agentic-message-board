package dev.agentboard.skill;

import dev.agentboard.config.BoardProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class SkillJobsTest {
    @TempDir Path temp;
    BoardProperties props;SkillCatalog catalog;Path bundle;CodexRuntime runtime;
    ObjectMapper json=new ObjectMapper();SkillJobService jobs;
    @BeforeEach void setup() throws Exception {
        props=new BoardProperties();props.setDataDir(temp.resolve("data").toString());
        Path installed=temp.resolve("plugins/cache/openai-primary-runtime");bundle=installed.resolve("pdf/1/skills/pdf");Files.createDirectories(bundle);
        Files.writeString(bundle.resolve("SKILL.md"),"---\nname: pdf\ndescription: Existing PDF skill\n---\nUse the included renderer.\n");
        Files.writeString(bundle.resolve("render.py"),"print('bundled renderer')\n");props.getCodex().setSkillRoots(List.of(installed.toString()));catalog=new SkillCatalog(props);
        runtime=new CodexRuntime(props,json){
            @Override public Map<String,Object> status(){return Map.of("available",true,"billing","Test executor: no model calls");}
            @Override public void requireModel(String model){if(!model.equals("listed-model"))throw new IllegalArgumentException("Unlisted model");}
        };
    }
    private void service(CodexJobExecutor executor) {
        var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
        db.execute("CREATE TABLE skill_jobs(id VARCHAR(36) PRIMARY KEY,status VARCHAR(20),created_at VARCHAR(40),finished_at VARCHAR(40),detail TEXT)");
        jobs=new SkillJobService(catalog,runtime,executor,db,json,props);
    }
    private CodexJobExecutor fake() {return new CodexJobExecutor(runtime,json){@Override public Result execute(Path workspace,String model,int seconds,String prompt,AtomicBoolean cancelled) throws Exception {
        assertThat(Files.readString(workspace.resolve("SKILL.md"))).contains("Existing PDF skill");assertThat(Files.exists(workspace.resolve("render.py"))).isTrue();
        assertThat(prompt).contains("./SKILL.md");Files.writeString(workspace.resolve("output/report.pdf"),"%PDF-fake-executor");
        return new Result(0,"Created the report using the installed skill.",Map.of("inputTokens",12L,"cachedInputTokens",0L,"outputTokens",3L));
    }};}
    private Map<String,Object> submit(String model,String format,List<org.springframework.web.multipart.MultipartFile> files) throws Exception {
        var s=catalog.list().get(0);return jobs.submit("PDF build",s.id(),s.revision(),model,format,60,"Create a report.",files);
    }
    private Map<String,Object> await(String id) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<until){var job=jobs.get(id);if(!List.of("running","queued").contains(job.get("status")))return job;Thread.sleep(10);}
        throw new AssertionError("Job did not complete");
    }
    @AfterEach void close(){if(jobs!=null)jobs.close();}
    @Test void discoversExistingBundlesAndRejectsDeletedOrChangedSelections() throws Exception {
        var selected=catalog.list().get(0);assertThat(selected.runnable()).isTrue();assertThat(selected.formats()).containsExactly("pdf");
        Files.writeString(bundle.resolve("render.py"),"changed renderer");
        assertThatThrownBy(()->catalog.require(selected.id(),selected.revision())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        Files.delete(bundle.resolve("SKILL.md"));assertThatThrownBy(()->catalog.require(selected.id(),selected.revision())).hasMessageContaining("410");
    }
    @Test void snapshotsSupportingFilesAndDetectsTampering() throws Exception {
        var selected=catalog.list().get(0);Path snapshot=catalog.snapshot(selected,temp.resolve("snapshot"));Files.writeString(bundle.resolve("render.py"),"later version");
        assertThat(Files.readString(snapshot.resolve("render.py"))).contains("bundled renderer");assertThat(SkillCatalog.revision(snapshot)).isEqualTo(selected.revision());
        Files.writeString(snapshot.resolve("render.py"),"tampered");assertThat(SkillCatalog.revision(snapshot)).isNotEqualTo(selected.revision());
    }
    @Test void otherInstalledSkillsAreVisibleButDisabled() throws Exception {
        Path other=bundle.getParent().resolve("other");Files.createDirectories(other);Files.writeString(other.resolve("SKILL.md"),"---\nname: other\ndescription: Another installed skill\n---\nInstructions\n");
        var selected=catalog.list().stream().filter(s->s.name().equals("other")).findFirst().orElseThrow();assertThat(selected.runnable()).isFalse();
        assertThatThrownBy(()->catalog.require(selected.id(),selected.revision())).hasMessageContaining("409");
    }
    @Test void validatesModelFormatUploadPathsAndTimeLimitsBeforeQueueing() throws Exception {
        service(fake());assertThatThrownBy(()->submit("xyz","pdf",List.of())).hasMessageContaining("Unlisted model");
        assertThatThrownBy(()->submit("listed-model","xyz",List.of())).hasMessageContaining("output format");
        assertThatThrownBy(()->submit("listed-model","pdf",List.of(new MockMultipartFile("files","../escape.pdf","application/pdf",new byte[]{1})))).hasMessageContaining("filenames");
        var s=catalog.list().get(0);assertThatThrownBy(()->jobs.submit("Job",s.id(),s.revision(),"listed-model","pdf",999,"Do task",List.of())).hasMessageContaining("time limit");
        assertThat(jobs.list()).isEmpty();
    }
    @Test void fakeBuildPersistsUsageParametersAndDownload() throws Exception {
        service(fake());String id=(String)submit("listed-model","pdf",List.of(new MockMultipartFile("files","input.txt","text/plain","task data".getBytes()))).get("id");
        var job=await(id);assertThat(job.get("status")).isEqualTo("success");assertThat(job.get("inputs")).isEqualTo(List.of("1-input.txt"));
        assertThat(job.get("usage").toString()).contains("inputTokens=12");assertThat(Files.readString(jobs.download(id,0))).contains("%PDF");
        assertThatThrownBy(()->jobs.download(id,99)).hasMessageContaining("404");assertThatThrownBy(()->jobs.get("../escape")).hasMessageContaining("404");
        assertThat(jobs.list()).hasSize(1);
    }
    @Test void completedProcessWithoutRequestedArtifactIsFailure() throws Exception {
        service(new CodexJobExecutor(runtime,json){@Override public Result execute(Path w,String m,int s,String p,AtomicBoolean c){return new Result(0,"No output",Map.of());}});
        assertThat(await((String)submit("listed-model","pdf",List.of()).get("id")).get("status")).isEqualTo("failed");
    }
    @Test void publishedStarterTasksUseTheSelectedModelAndProduceSkillArtifacts() throws Exception {
        Map<String,String> expected=Map.of("pdf","pdf","Documents","docx","Spreadsheets","xlsx","Presentations","pptx");
        for(String name:expected.keySet()) {
            Path folder=bundle.getParent().resolve(name);Files.createDirectories(folder);
            Files.writeString(folder.resolve("SKILL.md"),"---\nname: "+name+"\ndescription: Published test bundle\n---\nFollow this skill.\n");
        }
        AtomicInteger calls=new AtomicInteger();
        service(new CodexJobExecutor(runtime,json){@Override public Result execute(Path workspace,String model,int seconds,String prompt,AtomicBoolean cancelled) throws Exception {
            assertThat(model).isEqualTo("listed-model");assertThat(prompt).contains("Use the existing Codex skill in ./SKILL.md", "User instructions:");
            String manifest=Files.readString(workspace.resolve("SKILL.md"));
            String name=expected.keySet().stream().filter(n->manifest.contains("name: "+n+"\n")).findFirst().orElseThrow();
            Files.writeString(workspace.resolve("output/result."+expected.get(name)),"Fake model artifact");calls.incrementAndGet();
            return new Result(0,"Skill finished",Map.of("inputTokens",10L,"outputTokens",5L));
        }});
        for(var skill:catalog.list()) {
            @SuppressWarnings("unchecked") Map<String,String> starter=(Map<String,String>)skill.publicView().get("starterTask");
            assertThat(starter).containsKeys("name","description","instructions");
            String id=(String)jobs.submit(starter.get("name"),skill.id(),skill.revision(),"listed-model",skill.formats().get(0),120,starter.get("instructions"),List.of()).get("id");
            assertThat(await(id)).containsEntry("status","success").containsEntry("model","listed-model");
            assertThat(Files.readString(jobs.download(id,0))).isEqualTo("Fake model artifact");
        }
        assertThat(calls.get()).isEqualTo(4);
    }
    @Test void cancelsRunningJobAndPreservesCancelledStatus() throws Exception {
        CountDownLatch started=new CountDownLatch(1);
        service(new CodexJobExecutor(runtime,json){@Override public Result execute(Path w,String m,int s,String p,AtomicBoolean c) throws Exception {
            started.countDown();while(!c.get())Thread.sleep(10);throw new JobStopped("cancelled","Cancelled");
        }});
        String id=(String)submit("listed-model","pdf",List.of()).get("id");assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
        assertThat(jobs.cancel(id).get("status")).isEqualTo("cancelled");assertThat(await(id).get("status")).isEqualTo("cancelled");
    }
    @Test void symlinkedSkillFilesAreNeverOffered() throws Exception {
        Files.createSymbolicLink(bundle.resolve("outside"),temp);assertThat(catalog.list()).isEmpty();
    }
    @Test void runtimeUsesOnlyListedModelsAndDoesNotPassPilotCredentials() throws Exception {
        Path home=temp.resolve("codex-home");Files.createDirectories(home);props.getCodex().setHome(home.toString());
        Path command=temp.resolve("fake-codex");Files.writeString(command,"#!/bin/sh\nif [ \"$1\" = '--version' ]; then echo 'fake-codex'; else echo 'Logged in using ChatGPT'; fi\n");command.toFile().setExecutable(true);props.getCodex().setCommand(command.toString());
        Path deps=temp.resolve("deps");for(String executable:List.of("python/bin/python3","node/bin/node")){Path f=deps.resolve(executable);Files.createDirectories(f.getParent());Files.writeString(f,"#!/bin/sh\n");f.toFile().setExecutable(true);}props.getCodex().setRuntimeRoot(deps.toString());
        Files.writeString(home.resolve("models_cache.json"),"{\"models\":[{\"slug\":\"listed-model\",\"display_name\":\"Listed\",\"visibility\":\"list\"},{\"slug\":\"hidden\",\"visibility\":\"hidden\"}]}");
        CodexRuntime actual=new CodexRuntime(props,json);actual.requireModel("listed-model");assertThatThrownBy(()->actual.requireModel("hidden")).hasMessageContaining("catalog");
        var process=actual.command(temp,"listed-model",temp.resolve("result"));assertThat(process.command()).contains("--ignore-user-config","workspace-write","--ephemeral");
        assertThat(process.environment()).doesNotContainKeys("OPENAI_API_KEY","CODEX_API_KEY","DB_PASSWORD");
        Files.writeString(home.resolve("models_cache.json"),"{\"models\":[]}");assertThatThrownBy(()->actual.requireModel("listed-model")).hasMessageContaining("unavailable");
    }
    @Test void executorReadsUsageFromARealFakeProcessWithoutCallingCodex() throws Exception {
        Path script=temp.resolve("fake-process");
        Files.writeString(script,"#!/bin/sh\nwhile [ $# -gt 0 ]; do\n if [ \"$1\" = '--output-last-message' ]; then shift; result=\"$1\"; fi\n shift\ndone\ncat >/dev/null\nprintf 'Test process complete' > \"$result\"\nprintf '%s\\n' '{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":25,\"cached_input_tokens\":5,\"output_tokens\":9}}'\n");script.toFile().setExecutable(true);props.getCodex().setCommand(script.toString());
        Path workspace=temp.resolve("process/work");Files.createDirectories(workspace);
        var result=new CodexJobExecutor(new CodexRuntime(props,json),json).execute(workspace,"listed-model",3,"test stdin",new AtomicBoolean());
        assertThat(result.exitCode()).isZero();assertThat(result.message()).isEqualTo("Test process complete");
        assertThat(result.usage()).containsEntry("inputTokens",25L).containsEntry("cachedInputTokens",5L).containsEntry("outputTokens",9L);
    }
    @Test void executorStopsFakeProcessOnTimeout() throws Exception {
        Path script=temp.resolve("slow-fake-process");Files.writeString(script,"#!/bin/sh\ncat >/dev/null\nsleep 20\n");script.toFile().setExecutable(true);props.getCodex().setCommand(script.toString());
        Path workspace=temp.resolve("slow/work");Files.createDirectories(workspace);
        assertThatThrownBy(()->new CodexJobExecutor(new CodexRuntime(props,json),json).execute(workspace,"listed-model",0,"test",new AtomicBoolean()))
                .isInstanceOf(CodexJobExecutor.JobStopped.class).hasMessageContaining("Time limit");
    }
}
