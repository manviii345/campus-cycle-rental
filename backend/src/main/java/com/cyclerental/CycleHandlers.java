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

    /** POST /api/cycles - create a listing (FR-04). Priced per hour; availability days are added separately. */
    public static void create(HttpExchange ex) throws Exception {
        Map<String, Object> user = Auth.requireUser(ex.getRequestHeaders().getFirst("Authorization"));
        Map<String, Object> body = Api.bodyJson(ex);

        String title = Json.str(body, "title");
        boolean explicitHourly = body.get("pricePerHour") instanceof Number;
        double pricePerHour = hourlyRate(body);
        if (pricePerHour <= 0) throw new ApiException(400, "Price per hour must be greater than zero");

        // available_from/to are kept as a summary of the availability days (see syncAvailabilitySummary).
        // An explicit hourly rate confirms pricing; a converted legacy daily rate stays unconfirmed.
        long id = Db.execute(
            "INSERT INTO cycles (owner_id, title, brand, model, cycle_type, condition_note, description," +
            " price_per_day, price_per_hour, pricing_confirmed, location, available_from, available_to, status, created_at)" +
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '', '', 'AVAILABLE', " + Auth.now() + ")",
            Auth.userId(user), title,
            Json.strOrNull(body, "brand"), Json.strOrNull(body, "model"),
            Json.strOrNull(body, "cycleType"), Json.strOrNull(body, "condition"),
            Json.strOrNull(body, "description"), Math.round(pricePerHour * 24.0 * 100.0) / 100.0, pricePerHour,
            explicitHourly ? 1 : 0,
            Json.strOrNull(body, "location"));

        Api.sendJson(ex, 201, getCycle(id));
    }

    /** Hourly rate from the request body; accepts the legacy pricePerDay and converts it. */
    static double hourlyRate(Map<String, Object> body) {
        if (body.get("pricePerHour") instanceof Number) {
            return ((Number) body.get("pricePerHour")).doubleValue();
        }
        if (body.get("pricePerDay") instanceof Number) {
            return Math.round(((Number) body.get("pricePerDay")).doubleValue() / 24.0 * 100.0) / 100.0;
        }
        return 0;
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
     *   ?query=&type=&location=&minPrice=&maxPrice=&date=&timeFrom=&timeTo=
     * The date filter takes a single day with an optional pickup/return time
     * interval (15-minute steps) and only returns cycles the owner made
     * available that day, inside one of that day's time windows, with no
     * overlapping confirmed booking. minPrice/maxPrice compare the hourly rate.
     */
    public static void search(HttpExchange ex) throws Exception {
        Auth.requireUser(ex.getRequestHeaders().getFirst("Authorization")); // listings are visible to logged-in students only
        Map<String, String> p = Api.queryParams(ex);
        StringBuilder sql = new StringBuilder(
            "SELECT c.*, u.name AS owner_name FROM cycles c JOIN users u ON u.id = c.owner_id" +
            // pricing_confirmed: a listing accepts bookings only after the owner explicitly sets its hourly rate
            " WHERE c.status = 'AVAILABLE' AND c.pricing_confirmed = 1");
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
            sql.append(" AND c.price_per_hour >= ?");
            params.add(Double.parseDouble(p.get("minPrice")));
        }
        if (p.containsKey("maxPrice") && !p.get("maxPrice").isEmpty()) {
            sql.append(" AND c.price_per_hour <= ?");
            params.add(Double.parseDouble(p.get("maxPrice")));
        }
        String date = p.get("date");
        if (date != null && !date.isEmpty()) {
            if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) {
                throw new ApiException(400, "Date must be in YYYY-MM-DD format");
            }
            String timeFrom = p.get("timeFrom");
            String timeTo = p.get("timeTo");
            boolean hasTimes = timeFrom != null && !timeFrom.isEmpty() && timeTo != null && !timeTo.isEmpty();
            if (hasTimes) {
                timeFrom = BookingHandlers.validTimeOr(timeFrom, null);
                timeTo = BookingHandlers.validTimeOr(timeTo, null);
                // The day must have a window covering the requested interval (BR-04)...
                sql.append(" AND EXISTS (SELECT 1 FROM cycle_availability a WHERE a.cycle_id = c.id" +
                           " AND a.day = ? AND a.pickup_from <= ? AND a.return_by >= ?)");
                params.add(date); params.add(timeFrom); params.add(timeTo);
                // ...and no confirmed booking may overlap the requested slot (BR-05 / FR-13).
                sql.append(" AND NOT EXISTS (SELECT 1 FROM bookings b WHERE b.cycle_id = c.id" +
                           " AND b.status IN ('ACCEPTED','ACTIVE')" +
                           " AND (b.start_date || ' ' || b.start_time) <= ? AND (b.end_date || ' ' || b.end_time) >= ?)");
                params.add(date + " " + timeTo); params.add(date + " " + timeFrom);
            } else {
                // Date only: the owner must have made the cycle available that day.
                sql.append(" AND EXISTS (SELECT 1 FROM cycle_availability a WHERE a.cycle_id = c.id AND a.day = ?)");
                params.add(date);
            }
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
        Auth.requireUser(ex.getRequestHeaders().getFirst("Authorization")); // logged-in students only
        Map<String, Object> cycle = Db.queryOne(
            "SELECT c.*, u.name AS owner_name FROM cycles c JOIN users u ON u.id = c.owner_id WHERE c.id = ?", id);
        if (cycle == null || "REMOVED".equals(cycle.get("status"))) {
            throw new ApiException(404, "Cycle not found");
        }
        Api.sendJson(ex, 200, decorate(cycle, false));
    }

    /** PUT /api/cycles/{id} - edit listing (FR-05). Only the owner (BR-02). Availability days use the availability endpoints. */
    public static void update(HttpExchange ex, long id) throws Exception {
        Map<String, Object> user = Auth.requireUser(ex.getRequestHeaders().getFirst("Authorization"));
        mustOwn(user, id);
        Map<String, Object> body = Api.bodyJson(ex);

        String status = Json.strOrNull(body, "status");
        if (status != null && !status.matches("AVAILABLE|PAUSED|UNAVAILABLE")) {
            throw new ApiException(400, "Status must be AVAILABLE, PAUSED or UNAVAILABLE");
        }
        Double hourly = null;
        boolean confirmsPricing = false;
        if (body.get("pricePerHour") instanceof Number || body.get("pricePerDay") instanceof Number) {
            hourly = hourlyRate(body);
            if (hourly <= 0) throw new ApiException(400, "Price per hour must be greater than zero");
            // Only an explicit hourly rate counts as the owner confirming the price.
            confirmsPricing = body.get("pricePerHour") instanceof Number;
        }
        Double derivedDaily = hourly != null ? Math.round(hourly * 24.0 * 100.0) / 100.0 : null;

        Db.execute(
            "UPDATE cycles SET title = COALESCE(?, title), brand = COALESCE(?, brand), model = COALESCE(?, model)," +
            " cycle_type = COALESCE(?, cycle_type), condition_note = COALESCE(?, condition_note)," +
            " description = COALESCE(?, description), price_per_day = COALESCE(?, price_per_day)," +
            " price_per_hour = COALESCE(?, price_per_hour)," +
            " pricing_confirmed = CASE WHEN ? = 1 THEN 1 ELSE pricing_confirmed END," +
            " location = COALESCE(?, location), status = COALESCE(?, status)" +
            " WHERE id = ?",
            Json.strOrNull(body, "title"), Json.strOrNull(body, "brand"), Json.strOrNull(body, "model"),
            Json.strOrNull(body, "cycleType"), Json.strOrNull(body, "condition"),
            Json.strOrNull(body, "description"), derivedDaily, hourly,
            confirmsPricing ? 1 : 0,
            Json.strOrNull(body, "location"), status, id);

        Api.sendJson(ex, 200, getCycle(id));
    }

    // ---------- availability days (FR-09): the owner lists the days a cycle can be rented ----------

    /** GET /api/cycles/{id}/availability - the days and time windows, upcoming first. */
    public static void listAvailability(HttpExchange ex, long id) throws Exception {
        Auth.requireUser(ex.getRequestHeaders().getFirst("Authorization"));
        Api.sendJson(ex, 200, availabilityRows(id));
    }

    /** POST /api/cycles/{id}/availability  {day, pickupFrom, returnBy} - add or replace one day's window. */
    public static void addAvailability(HttpExchange ex, long id) throws Exception {
        Map<String, Object> user = Auth.requireUser(ex.getRequestHeaders().getFirst("Authorization"));
        mustOwn(user, id);
        Map<String, Object> body = Api.bodyJson(ex);

        String day = Json.str(body, "day");
        if (!day.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new ApiException(400, "Day must be in YYYY-MM-DD format");
        }
        if (day.compareTo(java.time.LocalDate.now().toString()) < 0) {
            throw new ApiException(400, "Please choose a day from today onwards");
        }
        String pickupFrom = BookingHandlers.validTimeOr(Json.str(body, "pickupFrom"), null);
        String returnBy = BookingHandlers.validTimeOr(Json.str(body, "returnBy"), null);
        if (pickupFrom.compareTo(returnBy) >= 0) {
            throw new ApiException(400, "The return-by time must be after the earliest pickup time");
        }

        Db.execute("DELETE FROM cycle_availability WHERE cycle_id = ? AND day = ?", id, day); // replace the day's window
        Db.execute(
            "INSERT INTO cycle_availability (cycle_id, day, pickup_from, return_by, created_at) VALUES (?, ?, ?, ?, " + Auth.now() + ")",
            id, day, pickupFrom, returnBy);
        syncAvailabilitySummary(id);
        Api.sendJson(ex, 200, availabilityRows(id));
    }

    /** DELETE /api/cycles/{id}/availability/{availId} - remove one day. */
    public static void deleteAvailability(HttpExchange ex, long id, long availId) throws Exception {
        Map<String, Object> user = Auth.requireUser(ex.getRequestHeaders().getFirst("Authorization"));
        mustOwn(user, id);
        Map<String, Object> row = Db.queryOne(
            "SELECT * FROM cycle_availability WHERE id = ? AND cycle_id = ?", availId, id);
        if (row == null) throw new ApiException(404, "Availability day not found");
        long booked = ((Number) Db.queryOne(
            "SELECT COUNT(*) AS n FROM bookings WHERE cycle_id = ? AND start_date = ?" +
            " AND status IN ('PENDING','ACCEPTED','ACTIVE')", id, row.get("day")).get("n")).longValue();
        if (booked > 0) {
            throw new ApiException(409, "A booking request exists for this day - reject or finish it before removing the day");
        }
        Db.execute("DELETE FROM cycle_availability WHERE id = ?", availId);
        syncAvailabilitySummary(id);
        Api.sendJson(ex, 200, availabilityRows(id));
    }

    /** Keeps cycles.available_from/to as a summary of the availability days ('' when none). */
    static void syncAvailabilitySummary(long cycleId) {
        Db.execute(
            "UPDATE cycles SET available_from = COALESCE((SELECT MIN(day) FROM cycle_availability WHERE cycle_id = ?), '')," +
            " available_to = COALESCE((SELECT MAX(day) FROM cycle_availability WHERE cycle_id = ?), '') WHERE id = ?",
            cycleId, cycleId, cycleId);
    }

    /** Availability rows for API output: upcoming days only, earliest first. */
    static List<Object> availabilityRows(long cycleId) {
        String today = java.time.LocalDate.now().toString();
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> a : Db.query(
                "SELECT * FROM cycle_availability WHERE cycle_id = ? AND day >= ? ORDER BY day LIMIT 30", cycleId, today)) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", a.get("id"));
            row.put("day", a.get("day"));
            row.put("pickupFrom", a.get("pickup_from"));
            row.put("returnBy", a.get("return_by"));
            out.add(row);
        }
        return out;
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
        Object hourly = c.get("price_per_hour");
        out.put("pricePerHour", hourly != null ? hourly
            : Math.round(((Number) c.get("price_per_day")).doubleValue() / 24.0 * 100.0) / 100.0);
        Object confirmed = c.get("pricing_confirmed");
        out.put("pricingConfirmed", confirmed != null && ((Number) confirmed).intValue() == 1);
        out.put("location", c.get("location"));
        out.put("availableFrom", c.get("available_from"));
        out.put("availableTo", c.get("available_to"));
        out.put("availability", availabilityRows(((Number) c.get("id")).longValue()));
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
