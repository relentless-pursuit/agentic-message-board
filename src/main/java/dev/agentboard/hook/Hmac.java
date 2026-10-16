package dev.agentboard.hook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * HMAC-SHA256 signatures over "timestamp.body", the same idea GitHub and Stripe webhooks use.
 * The timestamp stops an old, captured request from being replayed later.
 */
public final class Hmac {

    public static final String SIGNATURE_HEADER = "X-AgentBoard-Signature";
    public static final String TIMESTAMP_HEADER = "X-AgentBoard-Timestamp";
    public static final String DELIVERY_HEADER = "X-AgentBoard-Delivery";

    private Hmac() {
    }

    public static String sign(String secret, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time comparison, so the time taken does not leak how much of a guess was right. */
    public static boolean verify(String secret, String timestamp, String body, String signature) {
        if (signature == null || timestamp == null) return false;
        byte[] expected = sign(secret, timestamp, body).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, signature.trim().getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
