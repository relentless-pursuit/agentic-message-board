package dev.agentboard.security;

import dev.agentboard.config.BoardProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Rejects oversized API requests before anything reads them. Screenshot source, not registered in this build. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestSizeFilter extends OncePerRequestFilter {

    private final int maxBytes;

    public RequestSizeFilter(BoardProperties props) {
        this.maxBytes = props.getLimits().getMaxRequestBytes();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean api = request.getRequestURI().startsWith("/api/") || request.getRequestURI().equals("/mcp");
        boolean skillUpload = request.getMethod().equals("POST") && request.getRequestURI().equals("/api/skill-jobs")
                && request.getContentType()!=null && request.getContentType().startsWith("multipart/form-data");
        boolean swarmUpload=request.getMethod().equals("POST") && request.getRequestURI().equals("/api/swarm/source")
                && request.getContentType()!=null && request.getContentType().startsWith("multipart/form-data");
        int limit = skillUpload ? 21_000_000 : swarmUpload ? 2_100_000 : maxBytes;
        boolean chunked = "chunked".equalsIgnoreCase(String.valueOf(request.getHeader("Transfer-Encoding")).trim());
        if (api && chunked) {
            // A chunked body has no declared size, so it could be any size. Ask for Content-Length instead.
            response.sendError(411, "send a Content-Length header (at most " + maxBytes + " bytes)");
            return;
        }
        if (api && request.getContentLengthLong() > limit) {
            response.sendError(413, "request larger than " + limit + " bytes");
            return;
        }
        chain.doFilter(request, response);
    }
}
