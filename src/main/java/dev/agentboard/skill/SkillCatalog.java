package dev.agentboard.skill;

import dev.agentboard.config.BoardProperties;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Read installed skill bundles. User input never supplies a filesystem path or skill source. */
@Component
public class SkillCatalog {
    private final BoardProperties.Codex config;
    private static final Map<String,List<String>> FORMATS=Map.of("pdf",List.of("pdf"),"documents",List.of("docx"),
            "spreadsheets",List.of("xlsx","csv"),"presentations",List.of("pptx"));
    /** Board-authored task suggestions; the executor still runs the installed publisher's bundle. */
    private static Map<String,String> starterTask(String name) {
        return switch(name.toLowerCase(Locale.ROOT)) {
            case "pdf" -> Map.of("name","Engineering report", "description","Turn supplied notes or documents into a concise PDF report.",
                    "instructions","Create a concise PDF engineering report from my supplied material. Include a summary, key findings with evidence, recommendations and open questions. Clearly identify missing information and do not invent facts. Follow the PDF skill's rendering and validation workflow.");
            case "documents" -> Map.of("name","Engineering brief", "description","Draft a structured Word brief from requirements or project notes.",
                    "instructions","Create a Word engineering brief from my supplied material. Include the problem, requirements, proposed approach, risks and next steps. Separate facts from assumptions. Do not invent project details. Follow the Documents skill's rendering and validation workflow.");
            case "spreadsheets" -> Map.of("name","Issue analysis workbook", "description","Analyze an uploaded issue export or dataset in a workbook.",
                    "instructions","Analyze my uploaded issue export or dataset and create a workbook. Preserve the source data, summarize useful counts and trends, add formulas and one appropriate chart, and explain missing data. Use only columns that actually exist. Follow the Spreadsheets skill's calculation and validation workflow.");
            case "presentations" -> Map.of("name","Engineering update", "description","Create a short PowerPoint from project evidence and notes.",
                    "instructions","Create a concise five-slide engineering update from my supplied material: context, progress, evidence, risks and next steps. Use only supported facts and label assumptions. Follow the Presentations skill's rendering and validation workflow.");
            default -> Map.of();
        };
    }
    public SkillCatalog(BoardProperties props){config=props.getCodex();}
    public List<Skill> list() {
        Map<String,Skill> latest=new TreeMap<>();
        for(String folder:config.getSkillRoots()) {
            Path root=Path.of(folder).toAbsolutePath().normalize();
            if(!Files.isDirectory(root))continue;
            try(var paths=Files.walk(root,10)) {
                for(Path manifest:paths.filter(p->p.getFileName().toString().equalsIgnoreCase("SKILL.md") && Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList()) {
                    try {
                        Skill skill=read(manifest);
                        String key=skill.origin()+":"+skill.name();
                        Skill old=latest.get(key);
                        if(old==null || skill.modifiedMillis()>old.modifiedMillis())latest.put(key,skill);
                    } catch(Exception invalid) { /* Invalid manifests are omitted, never offered as executable choices. */ }
                }
            } catch(IOException ignored) { }
        }
        return latest.values().stream().sorted(Comparator.comparing(Skill::runnable).reversed().thenComparing(Skill::displayName)).toList();
    }
    private Skill read(Path manifest) throws Exception {
        String text=Files.readString(manifest);
        if(text.length()>200_000 || !text.startsWith("---"))throw new IOException("Invalid skill manifest");
        int end=text.indexOf("\n---",3);if(end<0)throw new IOException("Missing front matter");
        var loader=new LoaderOptions();loader.setAllowDuplicateKeys(false);loader.setMaxAliasesForCollections(10);
        Object raw=new Yaml(new SafeConstructor(loader)).load(text.substring(3,end));
        if(!(raw instanceof Map<?,?> data))throw new IOException("Invalid metadata");
        String name=Objects.toString(data.get("name"),"").trim(),description=Objects.toString(data.get("description"),"").trim();
        if(name.isBlank() || description.isBlank())throw new IOException("Missing metadata");
        String display=name;
        Path ui=manifest.getParent().resolve("agents/openai.yaml");
        if(Files.isRegularFile(ui)) {
            Object metadata=new Yaml(new SafeConstructor(loader)).load(Files.readString(ui));
            if(metadata instanceof Map<?,?> all && all.get("interface") instanceof Map<?,?> i)
                display=Objects.toString(i.get("display_name"),name);
        }
        Path bundle=manifest.getParent().toRealPath();
        String source=bundle.toString();String origin=source.contains("/plugins/cache/openai-")?"OpenAI installed bundle":source.contains("/.system/")?"Codex system":"Personal installed skill";
        List<String> formats=origin.equals("OpenAI installed bundle")?FORMATS.getOrDefault(name.toLowerCase(Locale.ROOT),List.of()):List.of();
        boolean runnable=!formats.isEmpty() && source.contains("/openai-primary-runtime/");
        String version="installed";
        int segment=source.indexOf("/skills/");
        if(segment>0)version=Path.of(source.substring(0,segment)).getFileName().toString();
        return new Skill(hash(source.getBytes(StandardCharsets.UTF_8)).substring(0,24),name,display,description,origin,version,
                revision(bundle),runnable,formats,runnable?"Existing local artifact skill; Codex runtime checked separately":"Installed; no local board executor configured for this skill",
                bundle.toString(),Files.getLastModifiedTime(manifest).toMillis());
    }
    public Skill require(String id,String revision) {
        Skill selected=list().stream().filter(s->s.id().equals(id)).findFirst()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.GONE,"That skill is no longer installed. Refresh the skill list."));
        if(!selected.runnable())throw new ResponseStatusException(HttpStatus.CONFLICT,selected.availability());
        if(!selected.revision().equals(revision))throw new ResponseStatusException(HttpStatus.CONFLICT,"The skill changed since selection. Refresh and review the current version.");
        return selected;
    }
    public Path snapshot(Skill skill,Path destination) throws Exception {
        Path source=Path.of(skill.path());Files.createDirectories(destination);
        for(Path file:files(source)) {
            Path target=destination.resolve(source.relativize(file));Files.createDirectories(target.getParent());Files.copy(file,target);
        }
        if(!revision(destination).equals(skill.revision()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Skill files changed while preparing the job. Refresh and retry.");
        return destination;
    }
    static List<Path> files(Path root) throws IOException {
        try(var walk=Files.walk(root)) {
            List<Path> paths=walk.sorted().toList();
            if(paths.stream().anyMatch(Files::isSymbolicLink))throw new IOException("Symlinked bundles are unsupported");
            List<Path> files=paths.stream().filter(Files::isRegularFile).toList();
            if(files.size()>500)throw new IOException("Skill has too many files");
            long total=0;for(Path p:files){long size=Files.size(p);if(size>25_000_000)throw new IOException("Skill file too large");total+=size;}
            if(total>50_000_000)throw new IOException("Skill bundle too large");return files;
        }
    }
    public static String revision(Path root) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(Path file:files(root)) {
            digest.update(root.relativize(file).toString().getBytes(StandardCharsets.UTF_8));digest.update((byte)0);
            digest.update(Files.readAllBytes(file));digest.update((byte)0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String hash(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    public record Skill(String id,String name,String displayName,String description,String origin,String version,String revision,
                        boolean runnable,List<String> formats,String availability,String path,long modifiedMillis) {
        public Map<String,Object> publicView(){return Map.ofEntries(Map.entry("id",id),Map.entry("name",name),Map.entry("displayName",displayName),
                Map.entry("description",description),Map.entry("origin",origin),Map.entry("version",version),Map.entry("revision",revision),
                Map.entry("runnable",runnable),Map.entry("formats",formats),Map.entry("availability",availability),
                Map.entry("starterTask",runnable?starterTask(name):Map.of()));}
    }
}
