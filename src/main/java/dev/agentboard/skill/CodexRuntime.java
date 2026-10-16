package dev.agentboard.skill;

import dev.agentboard.config.BoardProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Checks local executable/login and lists models advertised by the local Codex installation. */
@Component
public class CodexRuntime {
    private final BoardProperties.Codex config;
    private final ObjectMapper json;
    private volatile Map<String,Object> cached;
    private volatile long checkedAt;
    public CodexRuntime(BoardProperties props,ObjectMapper json){this.config=props.getCodex();this.json=json;}
    public synchronized Map<String,Object> status() {
        if(cached!=null && System.currentTimeMillis()-checkedAt<10_000)return cached;
        boolean executable=false,authenticated=false;String version="unavailable",auth="unavailable";
        try {
            var check=probe(List.of(config.getCommand(),"--version"));executable=check.code()==0;version=check.output().trim();
            var login=probe(List.of(config.getCommand(),"login","status"));authenticated=login.code()==0;
            auth=login.output().contains("ChatGPT")?"Current Codex login (ChatGPT)":authenticated?"Current Codex login":"Login required";
        }catch(Exception ignored){}
        List<Map<String,String>> models=new ArrayList<>();String fetched="";
        try {
            var cache=json.readTree(Files.readString(Path.of(config.getHome(),"models_cache.json")));
            fetched=cache.path("fetched_at").asText("");
            for(var model:cache.path("models"))if(model.path("visibility").asText().equals("list")) {
                String id=model.path("slug").asText();if(id.matches("[A-Za-z0-9._-]{1,80}"))
                    models.add(Map.of("id",id,"label",model.path("display_name").asText(id)));
            }
        }catch(Exception ignored){}
        boolean dependencies=Files.isExecutable(Path.of(config.getRuntimeRoot(),"python/bin/python3")) && Files.isExecutable(Path.of(config.getRuntimeRoot(),"node/bin/node"));
        cached=Map.of("available",executable && authenticated && dependencies && !models.isEmpty(),"executable",executable,
                "authenticated",authenticated,"authentication",auth,"version",version,"models",models,"modelsFetchedAt",fetched,
                "artifactRuntimeAvailable",dependencies,"billing","Uses the current Codex login; separate from the board's OpenAI pilot budget");
        checkedAt=System.currentTimeMillis();return cached;
    }
    public void requireModel(String model) {
        cached=null; // Re-read the installed model list at submission and execution, not just page load.
        if(!Boolean.TRUE.equals(status().get("available")))throw new IllegalArgumentException("Codex login, model catalog or artifact runtime is unavailable. Check runtime status first.");
        @SuppressWarnings("unchecked") var models=(List<Map<String,String>>)status().get("models");
        if(models.stream().noneMatch(m->m.get("id").equals(model)))throw new IllegalArgumentException("That Codex model is not in the current local catalog. Refresh the list.");
    }
    public ProcessBuilder command(Path workspace,String model,Path result) {
        var builder=new ProcessBuilder(config.getCommand(),"exec","--ignore-user-config","--ephemeral","--skip-git-repo-check",
                "--sandbox","workspace-write","-c","approval_policy=\"never\"","-c","model_reasoning_effort=\"low\"",
                "--model",model,"--json","--cd",workspace.toString(),"--output-last-message",result.toString(),"-");
        builder.directory(workspace.toFile());
        environment(builder);return builder;
    }
    private void environment(ProcessBuilder builder) {
        var env=builder.environment();var inherited=new HashMap<>(env);env.clear();
        for(String key:List.of("HOME","TMPDIR","PATH","SHELL","LANG","LC_ALL","USER","LOGNAME","SYSTEMROOT"))
            if(inherited.containsKey(key))env.put(key,inherited.get(key));
        env.put("CODEX_HOME",config.getHome());
        String root=config.getRuntimeRoot();
        env.put("PATH",root+"/python/bin:"+root+"/node/bin:"+root+"/bin/override:"+root+"/bin/fallback:"+env.getOrDefault("PATH",""));
        env.put("NODE_PATH",root+"/node/node_modules");
    }
    private Probe probe(List<String> command) throws Exception {
        var builder=new ProcessBuilder(command).redirectErrorStream(true);environment(builder);
        Process p=builder.start();p.getOutputStream().close();
        if(!p.waitFor(5,TimeUnit.SECONDS)){p.destroyForcibly();return new Probe(-1,"");}
        return new Probe(p.exitValue(),new String(p.getInputStream().readNBytes(4096),java.nio.charset.StandardCharsets.UTF_8));
    }
    public String runtimeRoot(){return config.getRuntimeRoot();}
    private record Probe(int code,String output){}
}
