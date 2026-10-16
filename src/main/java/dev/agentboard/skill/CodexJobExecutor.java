package dev.agentboard.skill;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One explicit CLI invocation; no retries or automatic paid executions. */
@Component
public class CodexJobExecutor {
    private final CodexRuntime runtime;
    private final ObjectMapper json;
    public CodexJobExecutor(CodexRuntime runtime,ObjectMapper json){this.runtime=runtime;this.json=json;}
    public Result execute(Path workspace,String model,int seconds,String prompt,AtomicBoolean cancelled) throws Exception {
        Path result=workspace.getParent().resolve("result.txt"),events=workspace.getParent().resolve("events.jsonl");
        Process process=runtime.command(workspace,model,result).redirectErrorStream(true).redirectOutput(events.toFile()).start();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        try {
            process.getOutputStream().write(prompt.getBytes(StandardCharsets.UTF_8));process.getOutputStream().close();
            while(!process.waitFor(100,TimeUnit.MILLISECONDS)) {
                if(cancelled.get())throw new JobStopped("cancelled","Cancelled by user; usage may already have accrued.");
                if(System.nanoTime()>deadline)throw new JobStopped("timed_out","Time limit reached; usage may already have accrued.");
                if(Files.size(events)>10_000_000)throw new JobStopped("failed","Execution log limit exceeded.");
            }
            if(cancelled.get())throw new JobStopped("cancelled","Cancelled by user; usage may already have accrued.");
            if(Files.size(events)>10_000_000)throw new JobStopped("failed","Execution log limit exceeded.");
            long input=0,cached=0,output=0;
            for(String line:Files.readAllLines(events))try {
                var event=json.readTree(line);
                if(event.path("type").asText().equals("turn.completed")) {
                    var usage=event.path("usage");input+=usage.path("input_tokens").asLong();
                    cached+=usage.path("cached_input_tokens").asLong();output+=usage.path("output_tokens").asLong();
                }
            }catch(Exception ignored){}
            String message="";
            if(Files.isRegularFile(result))try(var stream=Files.newInputStream(result)){message=new String(stream.readNBytes(80_000),StandardCharsets.UTF_8);}
            if(message.length()>20_000)message=message.substring(0,20_000);
            return new Result(process.exitValue(),message,Map.of("inputTokens",input,"cachedInputTokens",cached,"outputTokens",output));
        } finally {
            // Stop descendants even when the CLI exited or was interrupted before its child renderer.
            var children=process.descendants().toList();children.forEach(ProcessHandle::destroyForcibly);
            if(process.isAlive())process.destroyForcibly();
        }
    }
    public record Result(int exitCode,String message,Map<String,Long> usage){}
    public static class JobStopped extends Exception {
        public final String status;
        public JobStopped(String status,String message){super(message);this.status=status;}
    }
}
