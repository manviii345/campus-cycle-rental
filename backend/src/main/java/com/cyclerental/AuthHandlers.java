package com.cyclerental;

import com.sun.net.httpserver.HttpExchange;
import java.util.HashMap;
import java.util.Map;

/**
 * FR-01 registration, FR-02 login/logout, FR-03 profile management.
 */
public final class AuthHandlers {

    /**
     * Campus rule: only Thapar University email IDs may be used on this site.
     * Every Thapar student email ends with "@thapar.edu" (e.g. mgajrani_be24@thapar.edu).
     * This is an email-address check, not mailbox verification - the site does
     * not send verification email; it simply refuses non-Thapar addresses.
     */
    static final String THAPAR_DOMAIN = "@thapar.edu";

    static boolean isThaparEmail(String email) {
        return email != null && email.endsWith(THAPAR_DOMAIN) && email.length() > THAPAR_DOMAIN.length();
    }

    private AuthHandlers() {}

    /** POST /api/auth/register  {name, email, phone, password} */
    public static void register(HttpExchange ex) throws Exception {
        Map<String, Object> body = Api.bodyJson(ex);
        String name = Json.str(body, "name");
        String email = Json.str(body, "email").toLowerCase();
        String phone = Json.strOrNull(body, "phone");
        String password = Json.str(body, "password");

        if (name.length() < 2) throw new ApiException(400, "Please enter your full name");
        if (!email.contains("@")) throw new ApiException(400, "Please enter a valid email address");
        if (!isThaparEmail(email)) {
            throw new ApiException(400,
                "Only Thapar University email IDs (ending in @thapar.edu) can be used to create an account");
        }
        if (password.length() < 6) throw new ApiException(400, "Password must be at least 6 characters");

        if (Db.queryOne("SELECT id FROM users WHERE email = ?", email) != null) {
            throw new ApiException(409, "An account with this email already exists");
        }

        long id = Db.execute(
            "INSERT INTO users (name, email, phone, password_hash, created_at) VALUES (?, ?, ?, ?, " + Auth.now() + ")",
            name, email, phone, Auth.hashPassword(password));

        String token = Auth.createSession(id);
        Map<String, Object> response = new HashMap<>();
        response.put("token", token);
        response.put("user", publicSelf(Db.queryOne("SELECT * FROM users WHERE id = ?", id)));
        Api.sendJson(ex, 201, response);
    }

    /** POST /api/auth/login  {email, password} */
    public static void login(HttpExchange ex) throws Exception {
        Map<String, Object> body = Api.bodyJson(ex);
        String email = Json.str(body, "email").toLowerCase();
        String password = Json.str(body, "password");

        if (!isThaparEmail(email)) {
            throw new ApiException(400,
                "This website is only for Thapar University students. Please log in with your Thapar email ID (ending in @thapar.edu)");
        }

        Map<String, Object> user = Db.queryOne("SELECT * FROM users WHERE email = ?", email);
        if (user == null || !Auth.verifyPassword(password, (String) user.get("password_hash"))) {
            throw new ApiException(401, "Wrong email or password");
        }

        String token = Auth.createSession(Auth.userId(user));
        Map<String, Object> response = new HashMap<>();
        response.put("token", token);
        response.put("user", publicSelf(user));
        Api.sendJson(ex, 200, response);
    }

    /** POST /api/auth/logout */
    public static void logout(HttpExchange ex) throws Exception {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            Auth.deleteSession(header.substring("Bearer ".length()).trim());
        }
        Api.sendJson(ex, 200, java.util.Collections.singletonMap("ok", true));
    }

    /** GET /api/me - the logged-in user's own profile. */
    public static void me(HttpExchange ex) throws Exception {
        Map<String, Object> user = Auth.requireUser(ex.getRequestHeaders().getFirst("Authorization"));
        Api.sendJson(ex, 200, publicSelf(user));
    }

    /** PUT /api/me  {name, phone} - FR-03 profile update. */
    public static void updateMe(HttpExchange ex) throws Exception {
        Map<String, Object> user = Auth.requireUser(ex.getRequestHeaders().getFirst("Authorization"));
        Map<String, Object> body = Api.bodyJson(ex);
        String name = Json.strOrNull(body, "name");
        String phone = Json.strOrNull(body, "phone");
        if (name == null && phone == null) throw new ApiException(400, "Nothing to update");
        if (name != null && name.length() < 2) throw new ApiException(400, "Please enter your full name");

        Db.execute("UPDATE users SET name = COALESCE(?, name), phone = COALESCE(?, phone) WHERE id = ?",
            name, phone, Auth.userId(user));
        Api.sendJson(ex, 200, publicSelf(Db.queryOne("SELECT * FROM users WHERE id = ?", Auth.userId(user))));
    }

    /** The user's own view of their account (everything except the password hash). */
    static Map<String, Object> publicSelf(Map<String, Object> user) {
        Map<String, Object> out = new HashMap<>();
        out.put("id", user.get("id"));
        out.put("name", user.get("name"));
        out.put("email", user.get("email"));
        out.put("phone", user.get("phone"));
        out.put("createdAt", user.get("created_at"));
        return out;
    }
}
