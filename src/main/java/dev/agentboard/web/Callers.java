package dev.agentboard.web;

import dev.agentboard.security.BoardPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

final class Callers {

    private Callers() {
    }

    static Optional<BoardPrincipal> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof BoardPrincipal p ? Optional.of(p) : Optional.empty();
    }

    static String name() {
        return current().map(BoardPrincipal::name).orElse("anonymous");
    }

    static String ip(HttpServletRequest request) {
        return request == null ? null : request.getRemoteAddr();
    }
}
