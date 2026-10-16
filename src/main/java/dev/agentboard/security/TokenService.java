package dev.agentboard.security;

import dev.agentboard.audit.AuditLog;
import dev.agentboard.config.BoardProperties;
import dev.agentboard.hook.Hmac;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * API tokens. A token is shown exactly once; the board only keeps its SHA-256 hash, so a copy
 * of the database does not leak working tokens.
 * Like Jenkins' initialAdminPassword: on first start, if BOARD_ADMIN_TOKEN is not set, an admin
 * token is created and written to data/secrets/initial-admin-token for the operator to read.
 */
@Service
public class TokenService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    public static final Set<String> SCOPES = Set.of("post", "admin");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final BoardProperties props;
    private final AuditLog audit;
    private final Environment environment;

    public TokenService(JdbcClient jdbc, BoardProperties props, AuditLog audit, Environment environment) {
        this.jdbc = jdbc;
        this.props = props;
        this.audit = audit;
        this.environment = environment;
    }

    private static final String ENV_LABEL = "admin (BOARD_ADMIN_TOKEN)";
    private static final String INITIAL_LABEL = "initial admin token";

    @Override
    public void run(ApplicationArguments args) throws IOException {
        String fromEnv = environment.getProperty("BOARD_ADMIN_TOKEN");
        if (fromEnv != null && fromEnv.trim().length() >= 20) {
            String hash = Hmac.sha256(fromEnv.trim());
            store(fromEnv.trim(), null, Set.of("admin"), ENV_LABEL);
            // Rotating BOARD_ADMIN_TOKEN must lock out the old one (and the generated one).
            int revoked = jdbc.sql("UPDATE api_tokens SET revoked = TRUE WHERE agent_handle IS NULL AND label IN (?, ?) AND token_hash <> ? AND revoked = FALSE")
                    .params(ENV_LABEL, INITIAL_LABEL, hash).update();
            if (revoked > 0) {
                audit.record("system", "token.revoked", "admin", revoked + " old admin token(s) revoked after BOARD_ADMIN_TOKEN changed");
            }
            return;
        }
        if (props.openAccess()) {
            log.info("Open access (board.auth=open): a browser on this computer needs no token. Set BOARD_AUTH=token before sharing the board.");
            return;
        }
        boolean hasAdmin = jdbc.sql("SELECT COUNT(*) FROM api_tokens WHERE agent_handle IS NULL AND scopes LIKE '%admin%' AND revoked = FALSE")
                .query(Long.class).single() > 0;
        Path file = Path.of(props.getDataDir(), "secrets", "initial-admin-token");
        if (hasAdmin && Files.exists(file)) {
            return;
        }
        // The file is gone (or never existed): the old generated token can no longer be read, so retire it.
        jdbc.sql("UPDATE api_tokens SET revoked = TRUE WHERE agent_handle IS NULL AND label = ?").param(INITIAL_LABEL).update();
        String token = mint(null, Set.of("admin"), INITIAL_LABEL);
        Files.createDirectories(file.getParent());
        Files.writeString(file, token + System.lineSeparator());
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException windows) {
            // Windows has no POSIX permissions; the file lives in the git-ignored data folder.
        }
        log.info("Created an admin token. Read it from {}", file.toAbsolutePath());
    }

    /** Creates a new token and returns it. This is the only time the token itself exists in clear text. */
    public String mint(String agentHandle, Set<String> scopes, String label) {
        if (!SCOPES.containsAll(scopes) || scopes.isEmpty()) {
            throw new IllegalArgumentException("scopes must be some of " + SCOPES);
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = "ab_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        store(token, agentHandle, scopes, label);
        audit.record("system", "token.created", agentHandle == null ? "admin" : agentHandle, label + " scopes=" + scopes);
        return token;
    }

    private void store(String token, String agentHandle, Set<String> scopes, String label) {
        try {
            jdbc.sql("INSERT INTO api_tokens (token_hash, agent_handle, scopes, label, revoked, created_at) VALUES (?, ?, ?, ?, FALSE, ?)")
                    .params(Hmac.sha256(token), agentHandle, String.join(",", scopes), label, OffsetDateTime.now(ZoneOffset.UTC))
                    .update();
        } catch (DuplicateKeyException alreadyStored) {
            // Same token configured again (for example BOARD_ADMIN_TOKEN on every start).
        }
    }

    public Optional<BoardPrincipal> authenticate(String token) {
        if (token == null || !token.startsWith("ab_") || token.length() < 20) {
            return Optional.empty();
        }
        Optional<BoardPrincipal> principal = jdbc.sql("SELECT id, agent_handle, scopes, label FROM api_tokens WHERE token_hash = ? AND revoked = FALSE")
                .param(Hmac.sha256(token))
                .query((rs, n) -> {
                    String agent = rs.getString("agent_handle");
                    Set<String> scopes = new LinkedHashSet<>(Arrays.asList(rs.getString("scopes").split(",")));
                    return new BoardPrincipal(rs.getLong("id"), agent == null ? "admin" : agent, agent, scopes);
                })
                .optional();
        principal.ifPresent(p -> jdbc.sql("UPDATE api_tokens SET last_used_at = ? WHERE id = ?")
                .params(OffsetDateTime.now(ZoneOffset.UTC), p.tokenId()).update());
        return principal;
    }

    public void revokeForAgent(String agentHandle) {
        jdbc.sql("UPDATE api_tokens SET revoked = TRUE WHERE agent_handle = ?").param(agentHandle).update();
    }

    public List<TokenInfo> list() {
        return jdbc.sql("SELECT id, agent_handle, scopes, label, revoked, created_at, last_used_at FROM api_tokens ORDER BY id")
                .query((rs, n) -> new TokenInfo(rs.getLong("id"), rs.getString("agent_handle"), rs.getString("scopes"), rs.getString("label"),
                        rs.getBoolean("revoked"), rs.getObject("created_at", OffsetDateTime.class), rs.getObject("last_used_at", OffsetDateTime.class)))
                .list();
    }

    public record TokenInfo(long id, String agentHandle, String scopes, String label, boolean revoked, OffsetDateTime createdAt, OffsetDateTime lastUsedAt) {
    }
}
