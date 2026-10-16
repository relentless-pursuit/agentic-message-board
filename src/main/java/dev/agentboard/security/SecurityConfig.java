package dev.agentboard.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Diagram box "Security filter": reading the board is open (company visibility). Every write
 * needs a token with the right scope. Webhooks are checked by their HMAC signature instead.
 * No cookies or sessions are used, so cross-site request forgery does not apply.
 * Screenshot source; excluded until security dependencies, token schema and UI/API access are integrated.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, TokenService tokens) throws Exception {
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .requestCache(c -> c.disable())
                .addFilterBefore(new BearerTokenFilter(tokens), AnonymousAuthenticationFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; frame-ancestors 'none'")))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/api/hooks/**", "/mcp").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/posts").hasAnyAuthority("SCOPE_post", "SCOPE_admin")
                        .requestMatchers("/api/audit/**", "/api/tokens/**").hasAuthority("SCOPE_admin")
                        .requestMatchers("/api/benchmarks/**", "/api/model-usage").hasAuthority("SCOPE_admin")
                        .requestMatchers("/api/skill-jobs", "/api/skill-jobs/**").hasAuthority("SCOPE_admin")
                        .requestMatchers("/api/swarm", "/api/swarm/**").hasAuthority("SCOPE_admin")
                        .requestMatchers("/api/workflows", "/api/workflows/**").hasAuthority("SCOPE_admin")
                        .requestMatchers("/api/feed/**").hasAuthority("SCOPE_admin")
                        .requestMatchers(HttpMethod.POST, "/api/**").hasAuthority("SCOPE_admin")
                        .requestMatchers(HttpMethod.GET, "/**").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }

    /** No username/password users exist; turning this off also stops Spring printing a generated password. */
    @Bean
    UserDetailsService noPasswordUsers() {
        return username -> {
            throw new UsernameNotFoundException("this board only accepts API tokens");
        };
    }
}
