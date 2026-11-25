package com.cyclerental;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.Map;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Password hashing and session tokens.
 *
 * Passwords are never stored in plain text (NFR-01). We use PBKDF2 with a
 * random salt - a standard, deliberately slow hashing function built into the
 * JDK. The database stores "salt:hash", both Base64-encoded.
 *
 * Login issues a random token that the frontend sends in the
 * "Authorization: Bearer <token>" header. Tokens live in the sessions table
 * so they survive a server restart and can be revoked on logout.
 */
public final class Auth {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERATIONS = 65536;
    private static final int KEY_BITS = 256;

    private Auth() {}

    public static String hashPassword(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(password, salt);
        return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash);
    }

    public static boolean verifyPassword(String password, String stored) {
        String[] parts = stored.split(":");
        if (parts.length != 2) return false;
        byte[] salt = Base64.getDecoder().decode(parts[0]);
        byte[] expected = Base64.getDecoder().decode(parts[1]);
        byte[] actual = pbkdf2(password, salt);
        // Constant-time comparison so timing attacks cannot reveal the hash.
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] pbkdf2(String password, byte[] salt) {
        try {
            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 not available", e);
        }
    }

    /** Generates a new random session token. */
    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Creates a session for the user and returns the token. */
    public static String createSession(long userId) {
        String token = newToken();
        Db.execute("INSERT INTO sessions (token, user_id, created_at) VALUES (?, ?, " + now() + ")", token, userId);
        return token;
    }

    public static void deleteSession(String token) {
        Db.execute("DELETE FROM sessions WHERE token = ?", token);
    }

    /**
     * Resolves the Authorization header to the logged-in user's row,
     * or throws 401. Every protected endpoint starts with this call (NFR-02).
     */
    public static Map<String, Object> requireUser(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new ApiException(401, "Please log in first");
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        Map<String, Object> user = Db.queryOne(
            "SELECT u.* FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token = ?", token);
        if (user == null) {
            throw new ApiException(401, "Session expired, please log in again");
        }
        return user;
    }

    /** Current timestamp as "yyyy-MM-dd HH:mm:ss" (stored in every record for auditability, NFR-12). */
    public static String now() {
        return "'" + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()) + "'";
    }

    public static String nowString() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
    }

    public static long userId(Map<String, Object> user) {
        return ((Number) user.get("id")).longValue();
    }
}
