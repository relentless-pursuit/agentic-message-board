package dev.agentboard.swarm;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Ten project-owned agents, with an explicit dependency graph and pinned instructions per run. */
@Component
public class SwarmSkills {
    public record Agent(int id,String skill,String name,List<Integer> dependsOn,int outputTokens){}
    public static final List<Agent> AGENTS=List.of(
        new Agent(1,"resume-orchestrator","Orchestrator",List.of(),1200),
        new Agent(2,"resume-reader","CV and document extractor",List.of(),1400),
        new Agent(3,"resume-jd-analyst","Job requirements analyst",List.of(),1600),
        new Agent(4,"resume-fit-gap-analyst","CV fit and gap analyst",List.of(2,3),1400),
        new Agent(5,"resume-edit-proposer","Resume tailoring agent",List.of(2,3,4),2200),
        new Agent(6,"resume-claim-auditor","Evidence and claim verifier",List.of(2,5),1200),
        new Agent(7,"resume-consistency-reviewer","Consistency reviewer",List.of(3,5),1000),
        new Agent(8,"resume-intent-clarifier","User-intent clarifier",List.of(4,5,7),1000),
        new Agent(9,"resume-cover-letter-writer","Cover-letter drafter",List.of(1,2,3,4,5,8),1600),
        new Agent(10,"resume-final-verifier","Final reviewer",List.of(6,7,9),1200));
    public List<Map<String,Object>> snapshots() {
        return AGENTS.stream().map(a->{try {
            String text=new ClassPathResource("swarm/skills/"+a.skill()+"/SKILL.md").getContentAsString(StandardCharsets.UTF_8);
            Map<String,Object> step=new LinkedHashMap<>();step.put("id",a.id());step.put("skill",a.skill());step.put("name",a.name());
            step.put("dependsOn",a.dependsOn());step.put("status","waiting");step.put("instructions",text);
            step.put("outputTokenLimit",a.outputTokens());
            step.put("revision",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))));return step;
        }catch(Exception e){throw new IllegalStateException("A résumé swarm skill is missing",e);}}).toList();
    }
    public static Map<String,Object> schema(int agent) {
        var fields=new LinkedHashMap<String,Object>();fields.put("summary",text(300));
        fields.put("items",Map.of("type","array","items",text(220),"maxItems",6));
        if(agent==5){
            var edit=Map.of("type","object","properties",Map.of("before",text(400),"after",text(600),"reason",text(180),"evidence",text(300)),"required",List.of("before","after","reason","evidence"),"additionalProperties",false);
            fields.put("edits",Map.of("type","array","items",edit,"maxItems",4));
        }
        if(agent==8)fields.put("question",text(300));
        if(agent==9)fields.put("document",text(2000));
        return Map.of("type","object","properties",fields,"required",List.copyOf(fields.keySet()),"additionalProperties",false);
    }
    private static Map<String,Object> text(int limit){return Map.of("type","string","maxLength",limit);}
}
