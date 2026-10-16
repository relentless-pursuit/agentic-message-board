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
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads Authorization: Bearer ab_... and, if the token is valid, tells Spring Security who is calling.
 * With board.auth=open, a request without a token is treated as the local operator when it is
 * addressed to localhost and, if the browser sent an Origin, that Origin is this board itself.
 * The two checks stop other web sites (cross-site requests and DNS rebinding) from using the board.
 */
public class BearerTokenFilter extends OncePerRequestFilter {

    static final BoardPrincipal LOCAL = new BoardPrincipal(0, "local", null, Set.of("admin", "post"));

    private final TokenService tokens;
    private final boolean open;

    public BearerTokenFilter(TokenService tokens, boolean open) {
        this.tokens = tokens;
        this.open = open;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            tokens.authenticate(header.substring(7).trim()).ifPresent(BearerTokenFilter::signIn);
        } else if (open && isLocalRequest(request)) {
            signIn(LOCAL);
        }
        chain.doFilter(request, response);
    }

    private static void signIn(BoardPrincipal p) {
        List<SimpleGrantedAuthority> authorities = p.scopes().stream().map(s -> new SimpleGrantedAuthority("SCOPE_" + s)).toList();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p, null, authorities));
    }

    static boolean isLocalRequest(HttpServletRequest request) {
        if (!isLoopbackName(request.getServerName())) {
            return false;
        }
        String origin = request.getHeader("Origin");
        if (origin == null) {
            return true;
        }
        try {
            URI uri = URI.create(origin);
            return isLoopbackName(uri.getHost()) && portOf(uri) == request.getServerPort();
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    static boolean isLoopbackName(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        return h.equals("localhost") || h.endsWith(".localhost") || h.equals("::1") || h.matches("127(\\.\\d{1,3}){3}");
    }

    private static int portOf(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
