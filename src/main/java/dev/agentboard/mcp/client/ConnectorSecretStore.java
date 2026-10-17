package dev.agentboard.mcp.client;

import dev.agentboard.config.BoardProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Optional;

/** Private, encrypted credentials; connector records contain only opaque variable references. */
@Component
public class ConnectorSecretStore {
    private final Path directory;
    private final SecureRandom random = new SecureRandom();

    public ConnectorSecretStore(BoardProperties props) {
        directory = Path.of(props.getDataDir(), "secrets", "connectors");
    }

    static String name(String id) { return "BOARD_MCP_TOKEN_" + id.toUpperCase(java.util.Locale.ROOT).replace('-', '_'); }
    static String reference(String id) { return "${" + name(id) + "}"; }
    private Path file(String name) {
        if (!name.matches("BOARD_MCP_TOKEN_[A-Z0-9_]{2,30}")) throw new IllegalArgumentException("Invalid credential reference.");
        return directory.resolve(name + ".enc");
    }
    public boolean contains(String id) { return Files.isRegularFile(file(name(id))); }

    private void prepare() throws Exception {
        Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }
    private byte[] key(boolean create) throws Exception {
        Path path = directory.resolve("master.key");
        if (!Files.exists(path) && create) {
            byte[] key = new byte[32]; random.nextBytes(key);
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(path, key);
        }
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        byte[] key = Files.readAllBytes(path);
        if (key.length != 32) throw new IllegalStateException("Invalid connector encryption key.");
        return key;
    }
    public synchronized void put(String id, String token) {
        Path temp = null;
        try {
            prepare();
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(true), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(name(id).getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            temp = Files.createTempFile(directory, ".token-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(temp, ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
            try { Files.move(temp, file(name(id)), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file(name(id)), StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) {
            throw new IllegalStateException("Could not save the access token in private server storage. Check data-directory permissions.");
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (Exception ignored) { }
        }
    }
    public synchronized Optional<String> resolve(String name) {
        if (!name.startsWith("BOARD_MCP_TOKEN_")) return Optional.empty();
        Path path = file(name);
        if (!Files.exists(path)) return Optional.empty();
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length < 28) throw new IllegalStateException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(false), "AES"), new GCMParameterSpec(128, bytes, 0, 12));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            return Optional.of(new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException("Could not read the saved access token. Enter it again and save."); }
    }
    public synchronized void delete(String id) {
        try { Files.deleteIfExists(file(name(id))); }
        catch (Exception e) { throw new IllegalStateException("Could not remove the saved access token."); }
    }
}
