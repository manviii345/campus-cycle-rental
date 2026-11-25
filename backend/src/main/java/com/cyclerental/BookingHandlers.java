package com.cyclerental;

import com.sun.net.httpserver.HttpExchange;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bookings: the heart of the system.
 *
 * Lifecycle (FR-12):
 *   PENDING   renter sent a request, waiting for the owner
 *   ACCEPTED  owner accepted; payment can now be recorded (BR-06)
 *   REJECTED  owner declined
 *   CANCELLED renter cancelled before the rental started (FR-15)
 *   ACTIVE    both parties confirmed pickup (FR-19)
 *   COMPLETED both parties confirmed return (FR-20)
 */
public final class BookingHandlers {

    private BookingHandlers() {}

    /**
     * POST /api/bookings  {cycleId, startDate, endDate} - FR-10 rental request.
     * The total price is calculated here and returned so the renter sees the
     * amount before committing (FR-16, BR-09).
     */
    /** Default pickup/return times when the renter does not choose any. */
    static final String DEFAULT_START_TIME = "09:00";
    static final String DEFAULT_END_TIME = "18:00";

    /**
     * Returns the given HH:MM time if valid, otherwise the fallback.
     * Times are quarterly: the minutes must be 00, 15, 30 or 45 (user-confirmed rule).
     */
    static String validTimeOr(String value, String fallback) {
        if (value == null || value.isEmpty()) return fallback;
        if (!value.matches("^([01][0-9]|2[0-3]):[0-5][0-9]$")) {
            throw new ApiException(400, "Time must be in HH:MM format (e.g. 09:30)");
        }
        int minutes = Integer.parseInt(value.substring(3));
        if (minutes % 15 != 0) {
            throw new ApiException(400, "Times must be in 15-minute steps (e.g. 09:00, 09:15, 09:30, 09:45)");
        }
        return value;
    }

    /** Minutes between two HH:MM times on the same day. */
    static long minutesBetween(String startTime, String endTime) {
        return (Long.parseLong(endTime.substring(0, 2)) * 60 + Long.parseLong(endTime.substring(3)))
             - (Long.parseLong(startTime.substring(0, 2)) * 60 + Long.parseLong(startTime.substring(3)));
    }

    /**
     * Rental price: proportional to the exact time booked (user-confirmed rule:
     * 75 minutes costs 1.25 x the hourly rate). Rounded to the nearest paisa.
     */
    static double priceFor(double pricePerHour, String startTime, String endTime) {
        double hours = minutesBetween(startTime, endTime) / 60.0;
        return Math.round(hours * pricePerHour * 100.0) / 100.0;
    }

    /**
     * POST /api/bookings  {cycleId, date, startTime, endTime} - FR-10 rental request.
     * Rentals are single-day: the renter picks one of the owner's availability
     * days and a pickup/return time inside that day's window, and pays the
     * hourly rate for the exact time booked. The total price is calculated here
     * and returned so the renter sees the amount before committing (FR-16, BR-09).
     */
    public static void create(HttpExchange ex) throws Exception {
        Map<String, Object> user = Auth.requireUser(ex.getRequestHeaders().getFirst("Authorization"));
        Map<String, Object> body = Api.bodyJson(ex);
        long cycleId = (long) Json.num(body, "cycleId");
        String date = Json.strOrNull(body, "date");
        if (date == null) date = Json.strOrNull(body, "startDate"); // older clients sent startDate
        if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new ApiException(400, "Please choose a rental date in YYYY-MM-DD format");
        }
        String endDate = Json.strOrNull(body, "endDate");
        if (endDate != null && !endDate.equals(date)) {
            throw new ApiException(400, "Rentals are for a single day now - pick a date with a pickup and return time");
        }
        if (date.compareTo(LocalDate.now().toString()) < 0) {
            throw new ApiException(400, "Please choose a date from today onwards");
        }

        Map<String, Object> cycle = Db.queryOne("SELECT * FROM cycles WHERE id = ?", cycleId);
        if (cycle == null || "REMOVED".equals(cycle.get("status"))) throw new ApiException(404, "Cycle not found");
        Object confirmed = cycle.get("pricing_confirmed");
        if (confirmed == null || ((Number) confirmed).intValue() != 1) {
            throw new ApiException(409, "This listing is not bookable yet: the owner still needs to set its hourly price and availability");
        }

        long renterId = Auth.userId(user);
        long ownerId = ((Number) cycle.get("owner_id")).longValue();
        if (ownerId == renterId) throw new ApiException(400, "You cannot rent your own cycle");   // BR-03
        if (!"AVAILABLE".equals(cycle.get("status"))) throw new ApiException(409, "This cycle is not available right now");

        // BR-04: the owner makes the cycle available on specific days, each with a
        // pickup/return time window. The rental must fit inside that day's window.
        Map<String, Object> window = Db.queryOne(
            "SELECT * FROM cycle_availability WHERE cycle_id = ? AND day = ? AND confirmed = 1", cycleId, date);
        if (window == null) {
            throw new ApiException(409, "The owner has not made this cycle available on " + date);
        }
        String windowFrom = (String) window.get("pickup_from");
        String windowTo = (String) window.get("return_by");
        String startTime = validTimeOr(Json.strOrNull(body, "startTime"), windowFrom);
        String endTime = validTimeOr(Json.strOrNull(body, "endTime"), windowTo);
        if (startTime.compareTo(endTime) >= 0) {
            throw new ApiException(400, "The return time must be after the pickup time");
        }
        if (startTime.compareTo(windowFrom) < 0 || endTime.compareTo(windowTo) > 0) {
            throw new ApiException(409, "On " + date + " this cycle can be picked up from "
                + windowFrom + " and must be returned by " + windowTo);
        }
        // FR-13 / BR-05: no overlap with confirmed bookings (compares date + time).
        if (hasOverlap(cycleId, date, startTime, date, endTime)) {
            throw new ApiException(409, "This cycle is already booked for (part of) that period");
        }

        Number hourly = (Number) cycle.get("price_per_hour");
        double pricePerHour = hourly != null
            ? hourly.doubleValue()
            : Math.round(((Number) cycle.get("price_per_day")).doubleValue() / 24.0 * 100.0) / 100.0;
        double total = priceFor(pricePerHour, startTime, endTime);

        long id = Db.execute(
            "INSERT INTO bookings (cycle_id, renter_id, start_date, end_date, start_time, end_time, total_amount, status, created_at, updated_at)" +
            " VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', " + Auth.now() + ", " + Auth.now() + ")",
            cycleId, renterId, date, date, startTime, endTime, total);

        notifyUser(ownerId, "BOOKING_REQUEST",
            user.get("name") + " requested \"" + cycle.get("title") + "\" on " + date + " from " + startTime + " to " + endTime);

        Api.sendJson(ex, 201, getBooking(id, user));
    }

    /**
     * GET /api/bookings/mine?role=renter|owner - booking history (FR-14).
     * role=renter: bookings I made. role=owner: requests on my cycles.
     */
    public static void listMine(HttpExchange ex) throws Exception {
        Map<String, Object> user = Auth.requireUser(ex.getRequestHeaders().getFirst("Authorization"));
        String role = Api.queryParams(ex).getOrDefault("role", "renter");
        List<Map<String, Object>> rows;
        if ("owner".equals(role)) {
            rows = Db.query(
                "SELECT b.*, c.title AS cycle_title, c.owner_id, u.name AS renter_name, o.name AS owner_name FROM bookings b" +
                " JOIN cycles c ON c.id = b.cycle_id JOIN users u ON u.id = b.renter_id" +
                " JOIN users o ON o.id = c.owner_id" +
                " WHERE c.owner_id = ? ORDER BY b.id DESC", Auth.userId(user));
        } else {
            rows = Db.query(
                "SELECT b.*, c.title AS cycle_title, c.owner_id, u.name AS owner_name, r.name AS renter_name FROM bookings b" +
                " JOIN cycles c ON c.id = b.cycle_id JOIN users u ON u.id = c.owner_id" +
                " JOIN users r ON r.id = b.renter_id" +
                " WHERE b.renter_id = ? ORDER BY b.id DESC", Auth.userId(user));
        }
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> row : rows) out.add(decorate(row, user));
        Api.sendJson(ex, 200, out);
    }

    /** GET /api/bookings/{id} - one booking, visible only to the two parties. */
    public static void getOne(HttpExchange ex, long id) throws Exception {
        Map<String, Object> user = Auth.requireUser(ex.getRequestHeaders().getFirst("Authorization"));
        Map<String, Object> booking = loadForParty(id, user);
        Api.sendJson(ex, 200, decorate(booking, user));
    }

    /** POST /api/bookings/{id}/accept - owner accepts (FR-11). */
    public static void accept(HttpExchange ex, long id) throws Exception {
        Map<String, Object> user = Auth.requireUser(ex.getRequestHeaders().getFirst("Authorization"));
        Map<String, Object> result;
        // The server handles requests on several threads. The "check overlap, then
        // mark ACCEPTED" pair must not interleave between two accept calls, or two
        // overlapping bookings could both be accepted (FR-13, NFR-05). A single lock
        // makes the pair atomic inside this server process. The booking is re-read
        // inside the lock so the status and overlap check see the latest data.
        synchronized (ACCEPT_LOCK) {
            Map<String, Object> booking = loadForParty(id, user);
            mustBeOwner(booking, user);
            requireStatus(booking, "PENDING");
            if (hasOverlap(((Number) booking.get("cycle_id")).longValue(),
                    (String) booking.get("start_date"), (String) booking.get("start_time"),
                    (String) booking.get("end_date"), (String) booking.get("end_time"))) {
                throw new ApiException(409, "Another confirmed booking already overlaps this period");
            }
            setStatus(id, "ACCEPTED");
            notifyUser(((Number) booking.get("renter_id")).longValue(), "BOOKING_ACCEPTED",
                "Your request for \"" + booking.get("cycle_title") + "\" was accepted");
            result = getBooking(id, user);
        }
        Api.sendJson(ex, 200, result);
    }

    /** Serialises the overlap check and the ACCEPTED update (see accept). */
    private static final Object ACCEPT_LOCK = new Object();

    /** POST /api/bookings/{id}/reject - owner rejects (FR-11). */
    public static void reject(HttpExchange ex, long id) throws Exception {
        Map<String, Object> user = Auth.requireUser(ex.getRequestHeaders().getFirst("Authorization"));
        Map<String, Object> booking = loadForParty(id, user);
        mustBeOwner(booking, user);
        requireStatus(booking, "PENDING");
        setStatus(id, "REJECTED");
        notifyUser(((Number) booking.get("renter_id")).longValue(), "BOOKING_REJECTED",
            "Your request for \"" + booking.get("cycle_title") + "\" was rejected");
        Api.sendJson(ex, 200, getBooking(id, user));
    }

    /** POST /api/bookings/{id}/cancel - renter cancels a pending/accepted booking (FR-15). */
    public static void cancel(HttpExchange ex, long id) throws Exception {
        Map<String, Object> user = Auth.requireUser(ex.getRequestHeaders().getFirst("Authorization"));
        Map<String, Object> booking = loadForParty(id, user);
        if (((Number) booking.get("renter_id")).longValue() != Auth.userId(user)) {
            throw new ApiException(403, "Only the renter can cancel this booking");
        }
        String status = (String) booking.get("status");
        if (!status.equals("PENDING") && !status.equals("ACCEPTED")) {
            throw new ApiException(409, "A booking can only be cancelled before the cycle is picked up");
        }
        setStatus(id, "CANCELLED");
        notifyUser(((Number) booking.get("owner_id")).longValue(), "BOOKING_CANCELLED",
            "The booking for \"" + booking.get("cycle_title") + "\" was cancelled by the renter");
        Api.sendJson(ex, 200, getBooking(id, user));
    }

    /**
     * POST /api/bookings/{id}/payment  {method, transactionReference} - FR-17.
     * Payment is recorded manually (SRS 2.3 allows this when no payment
     * gateway is integrated). Only the renter, after acceptance.
     */
    public static void recordPayment(HttpExchange ex, long id) throws Exception {
        Map<String, Object> user = Auth.requireUser(ex.getRequestHeaders().getFirst("Authorization"));
        Map<String, Object> booking = loadForParty(id, user);
        if (((Number) booking.get("renter_id")).longValue() != Auth.userId(user)) {
            throw new ApiException(403, "Only the renter records payment for this booking");
        }
        requireStatus(booking, "ACCEPTED");
        if (Db.queryOne("SELECT id FROM payments WHERE booking_id = ?", id) != null) {
            throw new ApiException(409, "Payment is already recorded for this booking");
        }

        Map<String, Object> body = Api.bodyJson(ex);
        String method = Json.strOrNull(body, "method");
        String reference = Json.strOrNull(body, "transactionReference");
        Db.execute(
            "INSERT INTO payments (booking_id, amount, method, transaction_reference, status, paid_at)" +
            " VALUES (?, ?, ?, ?, 'RECORDED', " + Auth.now() + ")",
            id, booking.get("total_amount"), method, reference);

        notifyUser(((Number) booking.get("owner_id")).longValue(), "PAYMENT_RECORDED",
            "Payment of Rs. " + booking.get("total_amount") + " was recorded for \"" + booking.get("cycle_title") + "\"");
        Api.sendJson(ex, 200, getBooking(id, user));
    }

    /**
     * POST /api/bookings/{id}/pickup - FR-19 handover confirmation.
     * Both owner and renter confirm; the booking turns ACTIVE once both have.
     */
    public static void confirmPickup(HttpExchange ex, long id) throws Exception {
        Map<String, Object> user = Auth.requireUser(ex.getRequestHeaders().getFirst("Authorization"));
        Map<String, Object> booking = loadForParty(id, user);
        requireStatus(booking, "ACCEPTED");

        String column = isOwner(booking, user) ? "pickup_owner_at" : "pickup_renter_at";
        if (booking.get(column) != null) throw new ApiException(409, "You already confirmed pickup");
        Db.execute("UPDATE bookings SET " + column + " = " + Auth.now() + ", updated_at = " + Auth.now() + " WHERE id = ?", id);

        // If both sides have now confirmed, the rental is active.
        Map<String, Object> fresh = loadForParty(id, user);
        if (fresh.get("pickup_owner_at") != null && fresh.get("pickup_renter_at") != null) {
            setStatus(id, "ACTIVE");
        }
        notifyOtherParty(fresh, user, "PICKUP_CONFIRMED",
            user.get("name") + " confirmed pickup of \"" + booking.get("cycle_title") + "\"");
        Api.sendJson(ex, 200, getBooking(id, user));
    }

    /**
     * POST /api/bookings/{id}/return - FR-20 return management.
     * The renter initiates the return; the owner then marks the cycle returned,
     * which completes the rental and opens reviews (user-confirmed rule: the cycle
     * owner must be the one to mark it returned). The late fine stops when the
     * renter initiates, so the owner's confirmation delay never adds to it.
     */
    public static void confirmReturn(HttpExchange ex, long id) throws Exception {
        Map<String, Object> user = Auth.requireUser(ex.getRequestHeaders().getFirst("Authorization"));
        Map<String, Object> booking = loadForParty(id, user);
        requireStatus(booking, "ACTIVE");

        boolean owner = isOwner(booking, user);
        if (owner && booking.get("return_renter_at") == null) {
            throw new ApiException(409, "The renter has not initiated the return yet");
        }
        String column = owner ? "return_owner_at" : "return_renter_at";
        if (booking.get(column) != null) throw new ApiException(409, "You already confirmed the return");
        Db.execute("UPDATE bookings SET " + column + " = " + Auth.now() + ", updated_at = " + Auth.now() + " WHERE id = ?", id);

        Map<String, Object> fresh = loadForParty(id, user);
        if (fresh.get("return_owner_at") != null) {
            // owner marking the cycle returned completes the rental (renter already initiated)
            setStatus(id, "COMPLETED");
        }
        notifyOtherParty(fresh, user, owner ? "RETURN_CONFIRMED" : "RETURN_INITIATED",
            user.get("name") + (owner ? " marked \"" : " initiated the return of \"") + booking.get("cycle_title") + "\"" + (owner ? " as returned" : ""));
        Api.sendJson(ex, 200, getBooking(id, user));
    }

    /**
     * POST /api/bookings/{id}/reviews  {rating, comment} - FR-23.
     * Only after completion (BR-08), each party reviews the other once.
     */
    public static void addReview(HttpExchange ex, long id) throws Exception {
        Map<String, Object> user = Auth.requireUser(ex.getRequestHeaders().getFirst("Authorization"));
        Map<String, Object> booking = loadForParty(id, user);
        requireStatus(booking, "COMPLETED");

        Map<String, Object> body = Api.bodyJson(ex);
        long rating = (long) Json.num(body, "rating");
        if (rating < 1 || rating > 5) throw new ApiException(400, "Rating must be between 1 and 5");
        String comment = Json.strOrNull(body, "comment");

        long reviewerId = Auth.userId(user);
        long reviewedId = isOwner(booking, user)
            ? ((Number) booking.get("renter_id")).longValue()
            : ((Number) booking.get("owner_id")).longValue();

        if (Db.queryOne("SELECT id FROM reviews WHERE booking_id = ? AND reviewer_id = ?", id, reviewerId) != null) {
            throw new ApiException(409, "You already reviewed this booking");
        }
        Db.execute(
            "INSERT INTO reviews (booking_id, reviewer_id, reviewed_user_id, rating, comment, created_at)" +
            " VALUES (?, ?, ?, ?, ?, " + Auth.now() + ")",
            id, reviewerId, reviewedId, rating, comment);

        notifyUser(reviewedId, "NEW_REVIEW",
            user.get("name") + " left you a " + rating + "-star review");
        Api.sendJson(ex, 201, getBooking(id, user));
    }

    /** POST /api/bookings/{id}/report  {description} - FR-24 issue reporting. */
    public static void reportIssue(HttpExchange ex, long id) throws Exception {
        Map<String, Object> user = Auth.requireUser(ex.getRequestHeaders().getFirst("Authorization"));
        loadForParty(id, user); // must be a party to the booking
        String description = Json.str(Api.bodyJson(ex), "description");
        long reportId = Db.execute(
            "INSERT INTO issue_reports (booking_id, reporter_id, description, status, created_at)" +
            " VALUES (?, ?, ?, 'OPEN', " + Auth.now() + ")", id, Auth.userId(user), description);
        Map<String, Object> out = new HashMap<>();
        out.put("id", reportId);
        out.put("status", "OPEN");
        Api.sendJson(ex, 201, out);
    }

    // ---------- helpers ----------

    /**
     * True when the cycle has an ACCEPTED or ACTIVE booking overlapping the
     * requested window. Windows are compared as "YYYY-MM-DD HH:MM" strings,
     * which sort chronologically. Bookings made before times existed have
     * NULL times and fall back to full-day comparison.
     */
    static boolean hasOverlap(long cycleId, String start, String startTime, String end, String endTime) {
        Map<String, Object> row = Db.queryOne(
            "SELECT COUNT(*) AS n FROM bookings WHERE cycle_id = ? AND status IN ('ACCEPTED','ACTIVE')" +
            " AND (start_date || ' ' || COALESCE(start_time, '00:00')) <= ?" +
            " AND (end_date || ' ' || COALESCE(end_time, '23:59')) >= ?",
            cycleId, end + " " + endTime, start + " " + startTime);
        return ((Number) row.get("n")).longValue() > 0;
    }

    // ---------- late-return fine (calculated and displayed only, never charged automatically) ----------

    /** Fine per overdue day in Rs. Default 50; override with env LATE_FEE_PER_DAY. */
    static final long LATE_FEE_PER_DAY = Long.parseLong(Db.env("LATE_FEE_PER_DAY", "50"));

    /**
     * Overdue days = days after the booking's end date up to the return date.
     * The first overdue day is the day after end_date. The return date is the day the
     * RENTER initiated the return, so a slow owner confirmation never adds to the fine.
     * While the rental is still ACTIVE and the renter has not initiated the return,
     * today is used and the fine is shown as "so far" (fineFinal = false).
     */
    static long lateDays(String endDate, LocalDate returnDate) {
        return Math.max(0, ChronoUnit.DAYS.between(LocalDate.parse(endDate), returnDate));
    }

    static void addLateFee(Map<String, Object> out, Map<String, Object> b) {
        String status = (String) b.get("status");
        if (!"ACTIVE".equals(status) && !"COMPLETED".equals(status)) return;
        // The fine stops when the RENTER marks the cycle returned, so the owner's
        // confirmation delay never adds to it (user-confirmed rule). If only the
        // owner has marked it back, the fine keeps showing as "so far".
        Object stamp = b.get("return_renter_at");
        boolean fixed = stamp != null;
        LocalDate returnDate = fixed ? LocalDate.parse(stamp.toString().substring(0, 10)) : LocalDate.now();
        long days = lateDays((String) b.get("end_date"), returnDate);
        out.put("lateFeePerDay", LATE_FEE_PER_DAY);
        out.put("lateDays", days);
        out.put("fineAmount", days * LATE_FEE_PER_DAY);
        out.put("fineFinal", fixed);
    }

    /** Loads a booking with cycle/owner names, requiring the user to be a party (NFR-02/03). */
    static Map<String, Object> loadForParty(long id, Map<String, Object> user) {
        Map<String, Object> booking = Db.queryOne(
            "SELECT b.*, c.title AS cycle_title, c.owner_id, u.name AS renter_name," +
            " o.name AS owner_name FROM bookings b" +
            " JOIN cycles c ON c.id = b.cycle_id" +
            " JOIN users u ON u.id = b.renter_id JOIN users o ON o.id = c.owner_id WHERE b.id = ?", id);
        if (booking == null) throw new ApiException(404, "Booking not found");
        long me = Auth.userId(user);
        if (((Number) booking.get("renter_id")).longValue() != me
                && ((Number) booking.get("owner_id")).longValue() != me) {
            throw new ApiException(403, "This booking is not yours");
        }
        return booking;
    }

    static boolean isOwner(Map<String, Object> booking, Map<String, Object> user) {
        return ((Number) booking.get("owner_id")).longValue() == Auth.userId(user);
    }

    static void mustBeOwner(Map<String, Object> booking, Map<String, Object> user) {
        if (!isOwner(booking, user)) throw new ApiException(403, "Only the cycle owner can do this");
    }

    static void requireStatus(Map<String, Object> booking, String expected) {
        if (!expected.equals(booking.get("status"))) {
            throw new ApiException(409, "Booking is " + booking.get("status") + ", expected " + expected);
        }
    }

    static void setStatus(long id, String status) {
        Db.execute("UPDATE bookings SET status = ?, updated_at = " + Auth.now() + " WHERE id = ?", status, id);
    }

    static void notifyUser(long userId, String type, String message) {
        Db.execute(
            "INSERT INTO notifications (user_id, type, message, read_status, created_at) VALUES (?, ?, ?, 0, " + Auth.now() + ")",
            userId, type, message);
    }

    static void notifyOtherParty(Map<String, Object> booking, Map<String, Object> actor, String type, String message) {
        long other = isOwner(booking, actor)
            ? ((Number) booking.get("renter_id")).longValue()
            : ((Number) booking.get("owner_id")).longValue();
        notifyUser(other, type, message);
    }

    static Map<String, Object> getBooking(long id, Map<String, Object> user) {
        return decorate(loadForParty(id, user), user);
    }

    /** Shapes a booking row for the API, including payment, handover times, and reviews. */
    static Map<String, Object> decorate(Map<String, Object> b, Map<String, Object> viewer) {
        Map<String, Object> out = new HashMap<>();
        out.put("id", b.get("id"));
        out.put("cycleId", b.get("cycle_id"));
        out.put("cycleTitle", b.get("cycle_title"));
        out.put("renterId", b.get("renter_id"));
        out.put("renterName", b.get("renter_name"));
        out.put("ownerId", b.get("owner_id"));
        out.put("ownerName", b.get("owner_name"));
        out.put("startDate", b.get("start_date"));
        out.put("endDate", b.get("end_date"));
        out.put("startTime", b.get("start_time"));
        out.put("endTime", b.get("end_time"));
        out.put("totalAmount", b.get("total_amount"));
        out.put("status", b.get("status"));
        out.put("pickupOwnerConfirmedAt", b.get("pickup_owner_at"));
        out.put("pickupRenterConfirmedAt", b.get("pickup_renter_at"));
        out.put("returnOwnerConfirmedAt", b.get("return_owner_at"));
        out.put("returnRenterConfirmedAt", b.get("return_renter_at"));
        out.put("createdAt", b.get("created_at"));
        addLateFee(out, b);

        Map<String, Object> payment = Db.queryOne("SELECT * FROM payments WHERE booking_id = ?", b.get("id"));
        if (payment != null) {
            Map<String, Object> p = new HashMap<>();
            p.put("amount", payment.get("amount"));
            p.put("method", payment.get("method"));
            p.put("transactionReference", payment.get("transaction_reference"));
            p.put("status", payment.get("status"));
            p.put("paidAt", payment.get("paid_at"));
            out.put("payment", p);
        }

        // Contact details are only shared once the booking is accepted (NFR-03).
        String status = (String) b.get("status");
        if (status.equals("ACCEPTED") || status.equals("ACTIVE") || status.equals("COMPLETED")) {
            long otherId = isOwner(b, viewer)
                ? ((Number) b.get("renter_id")).longValue()
                : ((Number) b.get("owner_id")).longValue();
            Map<String, Object> other = Db.queryOne("SELECT email, phone FROM users WHERE id = ?", otherId);
            Map<String, Object> contact = new HashMap<>();
            contact.put("email", other.get("email"));
            contact.put("phone", other.get("phone"));
            out.put("otherPartyContact", contact);
        }

        List<Object> reviews = new ArrayList<>();
        for (Map<String, Object> r : Db.query(
                "SELECT r.*, u.name AS reviewer_name FROM reviews r JOIN users u ON u.id = r.reviewer_id" +
                " WHERE r.booking_id = ?", b.get("id"))) {
            Map<String, Object> review = new HashMap<>();
            review.put("reviewerName", r.get("reviewer_name"));
            review.put("rating", r.get("rating"));
            review.put("comment", r.get("comment"));
            review.put("createdAt", r.get("created_at"));
            reviews.add(review);
        }
        out.put("reviews", reviews);
        return out;
    }
}
