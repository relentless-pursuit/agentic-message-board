package dev.agentboard.swarm;

import dev.agentboard.config.BoardProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Explicit public-link/file import. Extracted text is previewed before the user starts a model. */
@Component
public class SwarmSources {
    private final BoardProperties props;private final ObjectMapper json;
    private final Set<String> hosts=Set.of("raw.githubusercontent.com","gist.githubusercontent.com","docs.google.com","boards.greenhouse.io","job-boards.greenhouse.io","jobs.lever.co");
    public SwarmSources(BoardProperties props,ObjectMapper json){this.props=props;this.json=json;}
    public Map<String,Object> importSource(String url,MultipartFile file) throws Exception {
        boolean uploaded=file!=null&&!file.isEmpty();
        if(uploaded==(url!=null&&!url.isBlank()))throw new IllegalArgumentException("Supply one public URL or one file.");
        byte[] bytes;
        if(uploaded){if(file.getSize()>2_000_000)throw new IllegalArgumentException("Upload a file up to 2 MB.");bytes=file.getBytes();}
        else {
            URI uri=URI.create(url.trim());String host=Objects.toString(uri.getHost(),"").toLowerCase(Locale.ROOT);
            if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo()!=null || (uri.getPort()!=-1 && uri.getPort()!=443) || !hosts.contains(host))
                throw new IllegalArgumentException("Use a public link from Google Docs, raw GitHub, Greenhouse or Lever, or upload/paste the document instead.");
            var client=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
            var response=client.send(HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(12)).header("User-Agent","agent-board-resume-demo/1").GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=response.body()) {if(response.statusCode()!=200)throw new IllegalArgumentException("That link needs a login, redirects, or is unavailable. Upload the file or paste its text instead.");bytes=stream.readNBytes(2_000_001);}
        }
        if(bytes.length>2_000_000)throw new IllegalArgumentException("Source exceeds 2 MB.");
        String script=new ClassPathResource("swarm/extract.py").getContentAsString(StandardCharsets.UTF_8);
        var outputFile=java.nio.file.Files.createTempFile("board-source-",".json");
        Process process=null;
        try {
            process=new ProcessBuilder(props.getCodex().getRuntimeRoot()+"/python/bin/python3","-c",script).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(outputFile.toFile()).start();
            process.getOutputStream().write(bytes);process.getOutputStream().close();
            if(!process.waitFor(15,TimeUnit.SECONDS))throw new IllegalArgumentException("Document extraction timed out. Paste its text instead.");
            if(java.nio.file.Files.size(outputFile)>250_000)throw new IllegalArgumentException("Extracted text is too large.");
            byte[] output=java.nio.file.Files.readAllBytes(outputFile);
            if(process.exitValue()!=0)throw new IllegalArgumentException("Cannot extract this file. Use a text PDF, DOCX or UTF-8 text file. Scanned PDFs need OCR first.");
            @SuppressWarnings("unchecked") Map<String,Object> result=json.readValue(output,LinkedHashMap.class);
            if(Objects.toString(result.get("text"),"").isBlank())throw new IllegalArgumentException("No text was found. Paste the text instead.");return result;
        }finally{if(process!=null)process.destroyForcibly();java.nio.file.Files.deleteIfExists(outputFile);}
    }
}
