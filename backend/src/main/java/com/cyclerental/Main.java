package com.cyclerental;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Entry point and HTTP router.
 *
 * The server is the JDK's built-in com.sun.net.httpserver.HttpServer - no web
 * framework, no application server. It serves three kinds of routes:
 *   /api/*     the JSON API (see the *Handlers classes)
 *   /photos/*  uploaded cycle photos from the uploads/ folder
 *   everything else: the built React frontend (frontend/dist), so a single
 *               "java com.cyclerental.Main" runs the whole application
 *
 * Configuration (all optional, see README):
 *   PORT         default 8080
 *   DB_URL       default local MySQL; "jdbc:sqlite:cyclerental.db" for zero-setup dev
 *   DB_USER / DB_PASSWORD
 *   UPLOAD_DIR   default "uploads"
 *   FRONTEND_DIR default "../frontend/dist"
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        Db.init(); // connect and create tables if needed
        if ("1".equals(Db.env("SEED_DEMO", ""))) {
            seedDemoData(); // demo dataset, only when the database is empty
        }

        int port = Integer.parseInt(Db.env("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", Main::route);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Cycle Rental server running at http://localhost:" + port);
        System.out.println("Database: " + (Db.SQLITE ? "SQLite" : "MySQL"));
    }

    /**
     * Optional demo dataset for hosted demos (SEED_DEMO=1). Seeds only when the
     * users table is empty, so restarts never wipe or duplicate real data.
     * Demo logins: do_be24@thapar.edu / dr_be24@thapar.edu, password "password1".
     */
    private static void seedDemoData() {
        long users = ((Number) Db.queryOne("SELECT COUNT(*) AS n FROM users").get("n")).longValue();
        if (users > 0) {
            return;
        }
        String hash = Auth.hashPassword("password1");
        long owner = Db.execute(
            "INSERT INTO users (name, email, phone, password_hash, created_at) VALUES (?, ?, ?, ?, " + Auth.now() + ")",
            "Demo Owner", "do_be24@thapar.edu", "9812345678", hash);
        Db.execute(
            "INSERT INTO users (name, email, phone, password_hash, created_at) VALUES (?, ?, ?, ?, " + Auth.now() + ")",
            "Demo Renter", "dr_be24@thapar.edu", "9812345679", hash);
        long hero = addDemoCycle(owner, "Hero Sprint 26T", "Hero", "Sprint 26T", "City", "Good",
            "Sturdy city bike, lock included.", 30.0, "Main Gate, TIET", "09:00", "18:00");
        long atlas = addDemoCycle(owner, "Atlas Trail MTB", "Atlas", "Trail MTB", "Mountain", "Good",
            "Mountain bike for rough paths and trails.", 40.0, "Hostel H", "08:00", "20:00");
        System.out.println("SEED_DEMO: demo owner/renter created, cycles " + hero + " and " + atlas + " listed for the next 7 days");
    }

    /** One demo listing with confirmed hourly pricing and 7 rentable days. */
    private static long addDemoCycle(long ownerId, String title, String brand, String model, String type,
            String condition, String description, double pricePerHour, String location, String from, String by) {
        java.time.LocalDate first = java.time.LocalDate.now();
        java.time.LocalDate last = first.plusDays(6);
        long id = Db.execute(
            "INSERT INTO cycles (owner_id, title, brand, model, cycle_type, condition_note, description," +
            " price_per_day, price_per_hour, pricing_confirmed, location, available_from, available_to, status, created_at)" +
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 'AVAILABLE', " + Auth.now() + ")",
            ownerId, title, brand, model, type, condition, description,
            Math.round(pricePerHour * 24.0 * 100.0) / 100.0, pricePerHour, location,
            first.toString(), last.toString());
        for (int i = 0; i < 7; i++) {
            Db.execute(
                "INSERT INTO cycle_availability (cycle_id, day, pickup_from, return_by, confirmed, created_at)" +
                " VALUES (?, ?, ?, ?, 1, " + Auth.now() + ")",
                id, first.plusDays(i).toString(), from, by);
        }
        return id;
    }

    /** Routes one request to the right handler. */
    private static void route(HttpExchange ex) {
        try {
            // Browsers send OPTIONS before cross-origin API calls during dev.
            if ("OPTIONS".equals(ex.getRequestMethod())) {
                Api.addCors(ex);
                ex.sendResponseHeaders(204, -1);
                ex.close();
                return;
            }

            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            String[] parts = path.split("/"); // parts[0] is empty

            if (path.equals("/health")) {
                MiscHandlers.health(ex);
                return;
            }

            if (path.startsWith("/api/")) {
                routeApi(ex, method, parts);
                return;
            }
            if (path.startsWith("/photos/")) {
                serveFile(ex, new File(CycleHandlers.UPLOAD_DIR), path.substring("/photos/".length()), false);
                return;
            }
            // Anything else is the React app (or its static assets).
            serveStatic(ex, path);
        } catch (ApiException e) {
            try { Api.sendError(ex, e.status, e.getMessage()); } catch (IOException ignored) {}
        } catch (Exception e) {
            e.printStackTrace();
            try { Api.sendError(ex, 500, "Unexpected server error"); } catch (IOException ignored) {}
        } finally {
            ex.close();
        }
    }

    private static void routeApi(HttpExchange ex, String method, String[] parts) throws Exception {
        // /api/<resource>/... -> parts: ["", "api", resource, ...]
        String resource = parts.length > 2 ? parts[2] : "";

        switch (resource) {
            case "auth": {
                String action = parts.length > 3 ? parts[3] : "";
                if (method.equals("POST") && action.equals("register")) { AuthHandlers.register(ex); return; }
                if (method.equals("POST") && action.equals("login")) { AuthHandlers.login(ex); return; }
                if (method.equals("POST") && action.equals("logout")) { AuthHandlers.logout(ex); return; }
                break;
            }
            case "me":
                if (method.equals("GET")) { AuthHandlers.me(ex); return; }
                if (method.equals("PUT")) { AuthHandlers.updateMe(ex); return; }
                break;
            case "users":
                if (method.equals("GET") && parts.length == 4) {
                    MiscHandlers.publicProfile(ex, parseId(parts[3]));
                    return;
                }
                break;
            case "cycles": {
                if (method.equals("POST") && parts.length == 3) { CycleHandlers.create(ex); return; }
                if (method.equals("GET") && parts.length == 3) { CycleHandlers.search(ex); return; }
                if (method.equals("GET") && parts.length == 4 && parts[3].equals("mine")) { CycleHandlers.listMine(ex); return; }
                if (parts.length >= 4) {
                    long id = parseId(parts[3]);
                    if (method.equals("GET") && parts.length == 4) { CycleHandlers.getOne(ex, id); return; }
                    if (method.equals("PUT") && parts.length == 4) { CycleHandlers.update(ex, id); return; }
                    if (method.equals("DELETE") && parts.length == 4) { CycleHandlers.remove(ex, id); return; }
                    if (method.equals("POST") && parts.length == 5 && parts[4].equals("photos")) {
                        CycleHandlers.addPhoto(ex, id); return;
                    }
                    if (method.equals("DELETE") && parts.length == 6 && parts[4].equals("photos")) {
                        CycleHandlers.deletePhoto(ex, id, parseId(parts[5])); return;
                    }
                    if (method.equals("GET") && parts.length == 5 && parts[4].equals("availability")) {
                        CycleHandlers.listAvailability(ex, id); return;
                    }
                    if (method.equals("POST") && parts.length == 5 && parts[4].equals("availability")) {
                        CycleHandlers.addAvailability(ex, id); return;
                    }
                    if (method.equals("DELETE") && parts.length == 6 && parts[4].equals("availability")) {
                        CycleHandlers.deleteAvailability(ex, id, parseId(parts[5])); return;
                    }
                }
                break;
            }
            case "bookings": {
                if (method.equals("POST") && parts.length == 3) { BookingHandlers.create(ex); return; }
                if (method.equals("GET") && parts.length == 4 && parts[3].equals("mine")) {
                    BookingHandlers.listMine(ex); return;
                }
                if (parts.length >= 4) {
                    long id = parseId(parts[3]);
                    if (method.equals("GET") && parts.length == 4) { BookingHandlers.getOne(ex, id); return; }
                    if (method.equals("POST") && parts.length == 5) {
                        switch (parts[4]) {
                            case "accept": BookingHandlers.accept(ex, id); return;
                            case "reject": BookingHandlers.reject(ex, id); return;
                            case "cancel": BookingHandlers.cancel(ex, id); return;
                            case "payment": BookingHandlers.recordPayment(ex, id); return;
                            case "pickup": BookingHandlers.confirmPickup(ex, id); return;
                            case "return": BookingHandlers.confirmReturn(ex, id); return;
                            case "reviews": BookingHandlers.addReview(ex, id); return;
                            case "report": BookingHandlers.reportIssue(ex, id); return;
                        }
                    }
                }
                break;
            }
            case "notifications":
                if (method.equals("GET") && parts.length == 3) { MiscHandlers.listNotifications(ex); return; }
                if (method.equals("POST") && parts.length == 4 && parts[3].equals("read-all")) {
                    MiscHandlers.markAllRead(ex); return;
                }
                break;
        }
        throw new ApiException(404, "No such API route: " + method + " " + ex.getRequestURI().getPath());
    }

    private static long parseId(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new ApiException(400, "Invalid id: " + s);
        }
    }

    // ---------- static file serving (frontend + photos) ----------

    private static void serveStatic(HttpExchange ex, String path) throws IOException {
        File root = new File(Db.env("FRONTEND_DIR", "../frontend/dist"));
        if (!root.isDirectory()) {
            Api.sendError(ex, 503, "Frontend not built yet. Run 'npm run build' in the frontend folder (see README).");
            return;
        }
        String relative = path.equals("/") ? "index.html" : path.substring(1);
        File file = new File(root, relative);
        if (!file.isFile()) {
            // SPA fallback: client-side routes like /cycles/3 are served index.html.
            relative = "index.html";
        }
        serveFile(ex, root, relative, true);
    }

    /** Serves one file, refusing paths that escape the given root folder. */
    private static void serveFile(HttpExchange ex, File root, String relative, boolean spa) throws IOException {
        File file = new File(root, relative).getCanonicalFile();
        if (!file.getPath().startsWith(root.getCanonicalFile().getPath()) || !file.isFile()) {
            Api.sendError(ex, 404, "Not found");
            return;
        }
        Api.sendBytes(ex, 200, contentType(file.getName()), Files.readAllBytes(file.toPath()));
    }

    private static String contentType(String name) {
        Map<String, String> types = new HashMap<>();
        types.put("html", "text/html; charset=utf-8");
        types.put("js", "text/javascript");
        types.put("css", "text/css");
        types.put("json", "application/json");
        types.put("png", "image/png");
        types.put("jpg", "image/jpeg");
        types.put("jpeg", "image/jpeg");
        types.put("svg", "image/svg+xml");
        types.put("ico", "image/x-icon");
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        return types.getOrDefault(ext, "application/octet-stream");
    }
}
