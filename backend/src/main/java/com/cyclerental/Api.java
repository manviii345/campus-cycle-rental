package com.cyclerental;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Small helpers shared by all HTTP handlers: reading request bodies, parsing
 * query strings, and sending JSON responses.
 */
public final class Api {

    private Api() {}

    /** Reads the request body as a UTF-8 string (limited to 1 MB for JSON endpoints). */
    public static String readBody(HttpExchange exchange) throws IOException {
        return new String(readBytes(exchange, 1024 * 1024), StandardCharsets.UTF_8);
    }

    /** Reads raw request bytes (used for photo uploads, limit 12 MB). */
    public static byte[] readBytes(HttpExchange exchange, int limit) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] buffer = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > limit) {
                    throw new ApiException(413, "Request too large");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** Parses the request body as a JSON object. */
    public static Map<String, Object> bodyJson(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        if (body.isEmpty()) return new HashMap<>();
        try {
            return Json.parseObject(body);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "Invalid JSON: " + e.getMessage());
        }
    }

    /** Parses "?key=value&..." into a map. */
    public static Map<String, String> queryParams(HttpExchange exchange) {
        Map<String, String> params = new HashMap<>();
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return params;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(urlDecode(pair.substring(0, eq)), urlDecode(pair.substring(eq + 1)));
            }
        }
        return params;
    }

    public static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    /** Sends a JSON response with the given status code. */
    public static void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] bytes = Json.write(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        addCors(exchange);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    public static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        Map<String, Object> error = new HashMap<>();
        error.put("error", message);
        sendJson(exchange, status, error);
    }

    /** Sends raw bytes (used to serve uploaded photos and the frontend files). */
    public static void sendBytes(HttpExchange exchange, int status, String contentType, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        addCors(exchange);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * CORS headers so the React dev server (localhost:5173) can call the API
     * during development. In the production demo the backend serves the
     * frontend itself, so no cross-origin request happens.
     */
    public static void addCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
    }

    /** Wraps handler logic with consistent error handling. */
    public interface Route {
        void handle(HttpExchange exchange, Map<String, String> pathVars) throws Exception;
    }

    public static void dispatch(HttpExchange exchange, Route route) {
        try {
            route.handle(exchange, new HashMap<>());
        } catch (ApiException e) {
            try { sendError(exchange, e.status, e.getMessage()); } catch (IOException ignored) {}
        } catch (Exception e) {
            e.printStackTrace();
            try { sendError(exchange, 500, "Unexpected server error"); } catch (IOException ignored) {}
        }
    }
}
