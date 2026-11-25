package com.cyclerental;

import com.sun.net.httpserver.HttpExchange;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cycle listings: creation and management (FR-04, FR-05, FR-09), discovery
 * and search (FR-06, FR-07), listing details (FR-08), and photo uploads.
 *
 * A "listing" and a "cycle" are the same record here: each row in the cycles
 * table is one cycle with its one active listing (SRS section 6.1).
 */
public final class CycleHandlers {

    /** Folder where uploaded cycle photos are stored. */
    static final String UPLOAD_DIR = Db.env("UPLOAD_DIR", "uploads");

    private CycleHandlers() {}

    /** POST /api/cycles - create a listing (FR-04). */
    public static void create(HttpExchange ex) throws Exception {
        Map<String, Object> user = Auth.requireUser(ex.getRequestHeaders().getFirst("Authorization"));
        Map<String, Object> body = Api.bodyJson(ex);

        String title = Json.str(body, "title");
        double pricePerDay = Json.num(body, "pricePerDay");
        String availableFrom = Json.str(body, "availableFrom");
        String availableTo = Json.str(body, "availableTo");
        validateDates(availableFrom, availableTo);
        if (pricePerDay <= 0) throw new ApiException(400, "Price per day must be greater than zero");

        long id = Db.execute(
            "INSERT INTO cycles (owner_id, title, brand, model, cycle_type, condition_note, description," +
            " price_per_day, location, available_from, available_to, status, created_at)" +
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'AVAILABLE', " + Auth.now() + ")",
            Auth.userId(user), title,
            Json.strOrNull(body, "brand"), Json.strOrNull(body, "model"),
            Json.strOrNull(body, "cycleType"), Json.strOrNull(body, "condition"),
            Json.strOrNull(body, "description"), pricePerDay,
            Json.strOrNull(body, "location"), availableFrom, availableTo);

        Api.sendJson(ex, 201, getCycle(id));
    }

    /** GET /api/cycles/mine - the logged-in user's own listings. */
    public static void listMine(HttpExchange ex) throws Exception {
        Map<String, Object> user = Auth.requireUser(ex.getRequestHeaders().getFirst("Authorization"));
        List<Map<String, Object>> rows = Db.query(
            "SELECT * FROM cycles WHERE owner_id = ? AND status != 'REMOVED' ORDER BY id DESC", Auth.userId(user));
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> row : rows) out.add(decorate(row, true));
        Api.sendJson(ex, 200, out);
    }

    /**
     * GET /api/cycles - browse and search (FR-06) with filters (FR-07):
     *   ?query=&type=&location=&minPrice=&maxPrice=&from=&to=
     * The from/to filter only returns cycles whose availability window fully
     * covers the requested period and that have no overlapping confirmed booking.
     */
    public static void search(HttpExchange ex) throws Exception {
        Map<String, String> p = Api.queryParams(ex);
        StringBuilder sql = new StringBuilder(
            "SELECT c.*, u.name AS owner_name FROM cycles c JOIN users u ON u.id = c.owner_id" +
            " WHERE c.status = 'AVAILABLE'");
        List<Object> params = new ArrayList<>();

        if (p.containsKey("query") && !p.get("query").isEmpty()) {
            sql.append(" AND (LOWER(c.title) LIKE ? OR LOWER(c.brand) LIKE ? OR LOWER(c.model) LIKE ? OR LOWER(c.description) LIKE ?)");
            String like = "%" + p.get("query").toLowerCase() + "%";
            params.add(like); params.add(like); params.add(like); params.add(like);
        }
        if (p.containsKey("type") && !p.get("type").isEmpty()) {
            sql.append(" AND c.cycle_type = ?");
            params.add(p.get("type"));
        }
        if (p.containsKey("location") && !p.get("location").isEmpty()) {
            sql.append(" AND LOWER(c.location) LIKE ?");
            params.add("%" + p.get("location").toLowerCase() + "%");
        }
        if (p.containsKey("minPrice") && !p.get("minPrice").isEmpty()) {
            sql.append(" AND c.price_per_day >= ?");
            params.add(Double.parseDouble(p.get("minPrice")));
        }
        if (p.containsKey("maxPrice") && !p.get("maxPrice").isEmpty()) {
            sql.append(" AND c.price_per_day <= ?");
            params.add(Double.parseDouble(p.get("maxPrice")));
        }
        String from = p.get("from");
        String to = p.get("to");
        if (from != null && !from.isEmpty() && to != null && !to.isEmpty()) {
            // Availability window must cover the whole rental period (BR-04)...
            sql.append(" AND c.available_from <= ? AND c.available_to >= ?");
            params.add(from); params.add(to);
            // ...and there must be no overlapping confirmed booking (BR-05 / FR-13).
            sql.append(" AND NOT EXISTS (SELECT 1 FROM bookings b WHERE b.cycle_id = c.id" +
                       " AND b.status IN ('ACCEPTED','ACTIVE') AND b.start_date <= ? AND b.end_date >= ?)");
            params.add(to); params.add(from);
        }
        sql.append(" ORDER BY c.id DESC");

        List<Object> out = new ArrayList<>();
        for (Map<String, Object> row : Db.query(sql.toString(), params.toArray())) {
            out.add(decorate(row, false));
        }
        Api.sendJson(ex, 200, out);
    }

    /** GET /api/cycles/{id} - full listing details (FR-08). */
    public static void getOne(HttpExchange ex, long id) throws Exception {
        Map<String, Object> cycle = Db.queryOne(
            "SELECT c.*, u.name AS owner_name FROM cycles c JOIN users u ON u.id = c.owner_id WHERE c.id = ?", id);
        if (cycle == null || "REMOVED".equals(cycle.get("status"))) {
            throw new ApiException(404, "Cycle not found");
        }
        Api.sendJson(ex, 200, decorate(cycle, false));
    }

    /** PUT /api/cycles/{id} - edit listing (FR-05). Only the owner (BR-02). */
    public static void update(HttpExchange ex, long id) throws Exception {
        Map<String, Object> user = Auth.requireUser(ex.getRequestHeaders().getFirst("Authorization"));
        Map<String, Object> cycle = mustOwn(user, id);
        Map<String, Object> body = Api.bodyJson(ex);

        String status = Json.strOrNull(body, "status");
        if (status != null && !status.matches("AVAILABLE|PAUSED|UNAVAILABLE")) {
            throw new ApiException(400, "Status must be AVAILABLE, PAUSED or UNAVAILABLE");
        }
        String availableFrom = Json.strOrNull(body, "availableFrom");
        String availableTo = Json.strOrNull(body, "availableTo");
        String newFrom = availableFrom != null ? availableFrom : (String) cycle.get("available_from");
        String newTo = availableTo != null ? availableTo : (String) cycle.get("available_to");
        validateDates(newFrom, newTo);

        Double price = body.get("pricePerDay") instanceof Number ? ((Number) body.get("pricePerDay")).doubleValue() : null;
        if (price != null && price <= 0) throw new ApiException(400, "Price per day must be greater than zero");

        Db.execute(
            "UPDATE cycles SET title = COALESCE(?, title), brand = COALESCE(?, brand), model = COALESCE(?, model)," +
            " cycle_type = COALESCE(?, cycle_type), condition_note = COALESCE(?, condition_note)," +
            " description = COALESCE(?, description), price_per_day = COALESCE(?, price_per_day)," +
            " location = COALESCE(?, location), available_from = ?, available_to = ?, status = COALESCE(?, status)" +
            " WHERE id = ?",
            Json.strOrNull(body, "title"), Json.strOrNull(body, "brand"), Json.strOrNull(body, "model"),
            Json.strOrNull(body, "cycleType"), Json.strOrNull(body, "condition"),
            Json.strOrNull(body, "description"), price, Json.strOrNull(body, "location"),
            newFrom, newTo, status, id);

        Api.sendJson(ex, 200, getCycle(id));
    }

    /** DELETE /api/cycles/{id} - removes the listing but keeps booking history (BR-10). */
    public static void remove(HttpExchange ex, long id) throws Exception {
        Map<String, Object> user = Auth.requireUser(ex.getRequestHeaders().getFirst("Authorization"));
        mustOwn(user, id);
        long active = ((Number) Db.queryOne(
            "SELECT COUNT(*) AS n FROM bookings WHERE cycle_id = ? AND status IN ('PENDING','ACCEPTED','ACTIVE')", id)
            .get("n")).longValue();
        if (active > 0) {
            throw new ApiException(409, "This cycle has active bookings. Pause the listing until they finish.");
        }
        Db.execute("UPDATE cycles SET status = 'REMOVED' WHERE id = ?", id);
        Api.sendJson(ex, 200, java.util.Collections.singletonMap("ok", true));
    }

    /**
     * POST /api/cycles/{id}/photos - upload one photo.
     * The photo is sent as the raw request body (Content-Type: image/jpeg or image/png),
     * which keeps the upload code short and easy to follow.
     */
    public static void addPhoto(HttpExchange ex, long id) throws Exception {
        Map<String, Object> user = Auth.requireUser(ex.getRequestHeaders().getFirst("Authorization"));
        mustOwn(user, id);

        String contentType = ex.getRequestHeaders().getFirst("Content-Type");
        String ext;
        if ("image/jpeg".equalsIgnoreCase(contentType)) ext = ".jpg";
        else if ("image/png".equalsIgnoreCase(contentType)) ext = ".png";
        else throw new ApiException(400, "Photo must be a JPEG or PNG image");

        byte[] bytes = Api.readBytes(ex, 12 * 1024 * 1024);
        if (bytes.length == 0) throw new ApiException(400, "Empty photo upload");

        new File(UPLOAD_DIR).mkdirs();
        String filename = UUID.randomUUID().toString() + ext;
        Files.write(new File(UPLOAD_DIR, filename).toPath(), bytes);

        long photoId = Db.execute(
            "INSERT INTO cycle_photos (cycle_id, filename, created_at) VALUES (?, ?, " + Auth.now() + ")",
            id, filename);

        Map<String, Object> out = new HashMap<>();
        out.put("id", photoId);
        out.put("url", "/photos/" + filename);
        Api.sendJson(ex, 201, out);
    }

    /** DELETE /api/cycles/{id}/photos/{photoId} */
    public static void deletePhoto(HttpExchange ex, long id, long photoId) throws Exception {
        Map<String, Object> user = Auth.requireUser(ex.getRequestHeaders().getFirst("Authorization"));
        mustOwn(user, id);
        Map<String, Object> photo = Db.queryOne(
            "SELECT * FROM cycle_photos WHERE id = ? AND cycle_id = ?", photoId, id);
        if (photo == null) throw new ApiException(404, "Photo not found");
        new File(UPLOAD_DIR, (String) photo.get("filename")).delete();
        Db.execute("DELETE FROM cycle_photos WHERE id = ?", photoId);
        Api.sendJson(ex, 200, java.util.Collections.singletonMap("ok", true));
    }

    // ---------- helpers ----------

    /** Loads the cycle and throws 404 when it does not exist. */
    static Map<String, Object> getCycle(long id) {
        Map<String, Object> cycle = Db.queryOne(
            "SELECT c.*, u.name AS owner_name FROM cycles c JOIN users u ON u.id = c.owner_id WHERE c.id = ?", id);
        if (cycle == null) throw new ApiException(404, "Cycle not found");
        return decorate(cycle, false);
    }

    /** Loads the cycle and checks the logged-in user owns it (BR-02). */
    static Map<String, Object> mustOwn(Map<String, Object> user, long id) {
        Map<String, Object> cycle = Db.queryOne("SELECT * FROM cycles WHERE id = ?", id);
        if (cycle == null || "REMOVED".equals(cycle.get("status"))) throw new ApiException(404, "Cycle not found");
        if (((Number) cycle.get("owner_id")).longValue() != Auth.userId(user)) {
            throw new ApiException(403, "You can only manage your own listings");
        }
        return cycle;
    }

    /** Adds photos and owner rating to a cycle row for API output. */
    static Map<String, Object> decorate(Map<String, Object> c, boolean includeAll) {
        Map<String, Object> out = new HashMap<>();
        out.put("id", c.get("id"));
        out.put("ownerId", c.get("owner_id"));
        out.put("ownerName", c.get("owner_name"));
        out.put("title", c.get("title"));
        out.put("brand", c.get("brand"));
        out.put("model", c.get("model"));
        out.put("cycleType", c.get("cycle_type"));
        out.put("condition", c.get("condition_note"));
        out.put("description", c.get("description"));
        out.put("pricePerDay", c.get("price_per_day"));
        out.put("location", c.get("location"));
        out.put("availableFrom", c.get("available_from"));
        out.put("availableTo", c.get("available_to"));
        out.put("status", c.get("status"));
        out.put("createdAt", c.get("created_at"));

        List<Object> photos = new ArrayList<>();
        for (Map<String, Object> p : Db.query("SELECT * FROM cycle_photos WHERE cycle_id = ? ORDER BY id", c.get("id"))) {
            Map<String, Object> photo = new HashMap<>();
            photo.put("id", p.get("id"));
            photo.put("url", "/photos/" + p.get("filename"));
            photos.add(photo);
        }
        out.put("photos", photos);

        // Owner trust signals: average rating and review count (FR-08 / FR-23).
        Map<String, Object> rating = Db.queryOne(
            "SELECT AVG(rating) AS avg_rating, COUNT(*) AS review_count FROM reviews WHERE reviewed_user_id = ?",
            c.get("owner_id"));
        out.put("ownerRating", rating.get("avg_rating"));
        out.put("ownerReviewCount", rating.get("review_count"));
        return out;
    }

    static void validateDates(String from, String to) {
        if (!from.matches("\\d{4}-\\d{2}-\\d{2}") || !to.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new ApiException(400, "Dates must be in YYYY-MM-DD format");
        }
        if (to.compareTo(from) < 0) {
            throw new ApiException(400, "End date must not be before the start date");
        }
    }
}
