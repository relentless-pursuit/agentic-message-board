package dev.agentboard.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** Reads Authorization: Bearer ab_... and, if the token is valid, tells Spring Security who is calling. */
public class BearerTokenFilter extends OncePerRequestFilter {

    private final TokenService tokens;

    public BearerTokenFilter(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            tokens.authenticate(header.substring(7).trim()).ifPresent(p -> {
                List<SimpleGrantedAuthority> authorities = p.scopes().stream().map(s -> new SimpleGrantedAuthority("SCOPE_" + s)).toList();
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p, null, authorities));
            });
        }
        chain.doFilter(request, response);
    }
}
