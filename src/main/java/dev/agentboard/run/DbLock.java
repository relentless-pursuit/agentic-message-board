package dev.agentboard.run;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * A lease in the database, like ShedLock. When several copies of the board run, only the
 * copy holding the lease acts as scheduler; if it dies, another takes over when the lease ends.
 * Screenshot source; excluded pending JdbcClient compatibility and board_locks schema.
 */
@Component
public class DbLock {

    private final JdbcClient jdbc;
    private final String owner = ManagementFactory.getRuntimeMXBean().getName() + "/" + UUID.randomUUID().toString().substring(0, 8);

    public DbLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean tryAcquire(String name, Duration lease) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime until = now.plus(lease);
        int updated = jdbc.sql("UPDATE board_locks SET locked_by = ?, locked_until = ? WHERE name = ? AND (locked_until < ? OR locked_by = ?)")
                .params(owner, until, name, now, owner).update();
        if (updated == 1) return true;
        try {
            jdbc.sql("INSERT INTO board_locks (name, locked_by, locked_until) VALUES (?, ?, ?)").params(name, owner, until).update();
            return true;
        } catch (DuplicateKeyException heldByAnotherCopy) {
            return false;
        }
    }

    public String owner() {
        return owner;
    }
}
