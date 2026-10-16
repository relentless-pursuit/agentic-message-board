package dev.agentboard.web;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(dev.agentboard.post.PostService.PostRejectedException.class)
    public ResponseEntity<Map<String,String>> postRejected(dev.agentboard.post.PostService.PostRejectedException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error",e.getMessage()));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,String>> badArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()==null?"bad request":e.getMessage()));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "Request failed" : e.getReason()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> invalidJson(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("message", "Request JSON does not match the expected agent definition."));
    }
}
