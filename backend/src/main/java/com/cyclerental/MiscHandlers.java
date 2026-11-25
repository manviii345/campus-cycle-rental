package com.cyclerental;

import com.sun.net.httpserver.HttpExchange;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Notifications (FR-21) and public user profiles with reviews (FR-08, FR-23).
 */
public final class MiscHandlers {

    private MiscHandlers() {}

    /** GET /api/notifications - newest first, for the logged-in user. */
    public static void listNotifications(HttpExchange ex) throws Exception {
        Map<String, Object> user = Auth.requireUser(ex.getRequestHeaders().getFirst("Authorization"));
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> n : Db.query(
                "SELECT * FROM notifications WHERE user_id = ? ORDER BY id DESC LIMIT 50", Auth.userId(user))) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", n.get("id"));
            item.put("type", n.get("type"));
            item.put("message", n.get("message"));
            item.put("read", ((Number) n.get("read_status")).intValue() == 1);
            item.put("createdAt", n.get("created_at"));
            out.add(item);
        }
        Api.sendJson(ex, 200, out);
    }

    /** POST /api/notifications/read-all - marks everything as read. */
    public static void markAllRead(HttpExchange ex) throws Exception {
        Map<String, Object> user = Auth.requireUser(ex.getRequestHeaders().getFirst("Authorization"));
        Db.execute("UPDATE notifications SET read_status = 1 WHERE user_id = ?", Auth.userId(user));
        Api.sendJson(ex, 200, java.util.Collections.singletonMap("ok", true));
    }

    /**
     * GET /api/users/{id} - public profile: name, member-since, average
     * rating, and the reviews other users left. No email or phone (NFR-03) -
     * contact details are shared only inside an accepted booking.
     */
    public static void publicProfile(HttpExchange ex, long id) throws Exception {
        Map<String, Object> user = Db.queryOne("SELECT * FROM users WHERE id = ?", id);
        if (user == null) throw new ApiException(404, "User not found");

        Map<String, Object> out = new HashMap<>();
        out.put("id", user.get("id"));
        out.put("name", user.get("name"));
        out.put("createdAt", user.get("created_at"));

        Map<String, Object> rating = Db.queryOne(
            "SELECT AVG(rating) AS avg_rating, COUNT(*) AS review_count FROM reviews WHERE reviewed_user_id = ?", id);
        out.put("avgRating", rating.get("avg_rating"));
        out.put("reviewCount", rating.get("review_count"));

        List<Object> reviews = new ArrayList<>();
        for (Map<String, Object> r : Db.query(
                "SELECT r.*, u.name AS reviewer_name FROM reviews r JOIN users u ON u.id = r.reviewer_id" +
                " WHERE r.reviewed_user_id = ? ORDER BY r.id DESC LIMIT 20", id)) {
            Map<String, Object> review = new HashMap<>();
            review.put("reviewerName", r.get("reviewer_name"));
            review.put("rating", r.get("rating"));
            review.put("comment", r.get("comment"));
            review.put("createdAt", r.get("created_at"));
            reviews.add(review);
        }
        out.put("reviews", reviews);
        Api.sendJson(ex, 200, out);
    }

    /** GET /health - simple liveness check. */
    public static void health(HttpExchange ex) throws Exception {
        Api.sendJson(ex, 200, java.util.Collections.singletonMap("status", "ok"));
    }
}
