package dev.agentboard.web;

import dev.agentboard.swarm.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/swarm")
public class ResumeSwarmController {
    private final ResumeSwarmService service;private final SwarmSources sources;
    public ResumeSwarmController(ResumeSwarmService service,SwarmSources sources){this.service=service;this.sources=sources;}
    @GetMapping("/options") public Map<String,Object> options(){return service.options();}
    @GetMapping public List<Map<String,Object>> list(){return service.list();}
    public record Start(String requestKey,String name,String resume,String jd){}
    @PostMapping public ResponseEntity<Map<String,Object>> start(@RequestBody Start input){return ResponseEntity.accepted().body(service.start(input.requestKey(),input.name(),input.resume(),input.jd()));}
    @GetMapping("/{id}") public Map<String,Object> get(@PathVariable long id){return service.get(id);}
    public record Review(List<ResumeSwarmService.Choice> choices,String customResume,String userMotivation){}
    @PostMapping("/{id}/approve") public Map<String,Object> approve(@PathVariable long id,@RequestBody Review input){return service.approve(id,input.choices(),input.customResume(),input.userMotivation());}
    public record Final(String coverLetter){}
    @PostMapping("/{id}/finalize") public Map<String,Object> finalizeDocuments(@PathVariable long id,@RequestBody Final input){return service.finalizeDocuments(id,input.coverLetter());}
    @PostMapping("/{id}/cancel") public Map<String,Object> cancel(@PathVariable long id){return service.cancel(id);}
    @PostMapping(value="/source",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> source(@RequestParam(required=false) String url,@RequestParam(required=false) MultipartFile file)throws Exception{return sources.importSource(url,file);}
    @GetMapping("/{id}/documents/{file}") public ResponseEntity<byte[]> document(@PathVariable long id,@PathVariable String file)throws Exception{
        if(!List.of("resume.docx","cover-letter.docx","resume.txt","cover-letter.txt").contains(file))throw new IllegalArgumentException("Unknown document.");
        String text=service.document(id,file.startsWith("cover"));boolean word=file.endsWith("docx");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(word?"application/vnd.openxmlformats-officedocument.wordprocessingml.document":"text/plain; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file).build().toString())
                .body(word?SwarmDocuments.docx(text):text.getBytes(StandardCharsets.UTF_8));
    }
}
