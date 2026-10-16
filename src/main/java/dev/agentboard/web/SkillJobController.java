package dev.agentboard.web;

import dev.agentboard.skill.SkillJobService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import java.util.*;

@RestController
@RequestMapping("/api/skill-jobs")
public class SkillJobController {
    private final SkillJobService jobs;
    public SkillJobController(SkillJobService jobs){this.jobs=jobs;}
    @GetMapping("/options") public Map<String,Object> options(){return jobs.options();}
    @GetMapping public List<Map<String,Object>> list(){return jobs.list();}
    @GetMapping("/{id}") public Map<String,Object> get(@PathVariable String id){return jobs.get(id);}
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String,Object>> submit(@RequestParam String name,@RequestParam String skillId,@RequestParam String revision,
            @RequestParam String model,@RequestParam String format,@RequestParam int seconds,@RequestParam String instructions,
            @RequestParam(required=false) List<MultipartFile> files) throws Exception {
        return ResponseEntity.accepted().body(jobs.submit(name,skillId,revision,model,format,seconds,instructions,files));
    }
    @PostMapping("/{id}/cancel") public Map<String,Object> cancel(@PathVariable String id){return jobs.cancel(id);}
    @GetMapping("/{id}/artifacts/{index}") public ResponseEntity<FileSystemResource> artifact(@PathVariable String id,@PathVariable int index) throws Exception {
        var file=jobs.download(id,index);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(java.nio.file.Files.size(file))
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.getFileName().toString(),java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(file));
    }
}
