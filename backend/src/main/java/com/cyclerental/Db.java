package com.cyclerental;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Database access layer.
 *
 * Configuration comes from environment variables (see README):
 *   DB_URL       JDBC connection string. Default: local MySQL database "cyclerental".
 *                For a zero-setup demo you can use "jdbc:sqlite:cyclerental.db".
 *   DB_USER      database username (default "root")
 *   DB_PASSWORD  database password (default empty)
 *
 * The SQL here is plain JDBC with prepared statements - no ORM - so every
 * query is visible and easy to explain. All user input goes through '?'
 * parameters, which prevents SQL injection.
 */
public final class Db {

    private static final String URL = env("DB_URL",
        "jdbc:mysql://localhost:3306/cyclerental?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true");
    private static final String USER = env("DB_USER", "root");
    private static final String PASSWORD = env("DB_PASSWORD", "");

    /** True when running against SQLite (used for the zero-setup dev mode). */
    public static final boolean SQLITE = URL.startsWith("jdbc:sqlite");

    static {
        try {
            if (SQLITE) {
                Class.forName("org.sqlite.JDBC");
            } else {
                Class.forName("com.mysql.cj.jdbc.Driver");
            }
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(
                "JDBC driver not found on the classpath. Run with: java -cp \"bin:lib/*\" com.cyclerental.Main");
        }
    }

    private Db() {}

    static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? fallback : v;
    }

    public static Connection getConnection() throws SQLException {
        Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
        if (SQLITE) {
            // Make SQLite enforce foreign keys (MySQL always does).
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
            }
        }
        return c;
    }

    /**
     * Creates the tables if they do not exist yet, so the app runs against a
     * fresh database without a separate migration step. The schema mirrors
     * the data model in section 6 of the SRS.
     */
    public static void init() {
        // MySQL and SQLite differ only in the auto-increment syntax.
        String pk = SQLITE ? "INTEGER PRIMARY KEY AUTOINCREMENT"
                           : "BIGINT PRIMARY KEY AUTO_INCREMENT";
        String now = SQLITE ? "TEXT" : "DATETIME";

        String[] statements = {
            "CREATE TABLE IF NOT EXISTS users (" +
                "id " + pk + ", " +
                "name VARCHAR(100) NOT NULL, " +
                "email VARCHAR(190) NOT NULL UNIQUE, " +
                "phone VARCHAR(30), " +
                "password_hash VARCHAR(255) NOT NULL, " +
                "created_at " + now + " NOT NULL)",

            "CREATE TABLE IF NOT EXISTS sessions (" +
                "token VARCHAR(64) PRIMARY KEY, " +
                "user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE, " +
                "created_at " + now + " NOT NULL)",

            "CREATE TABLE IF NOT EXISTS cycles (" +
                "id " + pk + ", " +
                "owner_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE, " +
                "title VARCHAR(150) NOT NULL, " +
                "brand VARCHAR(100), " +
                "model VARCHAR(100), " +
                "cycle_type VARCHAR(50), " +           // e.g. Road, Mountain, Hybrid, City, Electric
                "condition_note VARCHAR(50), " +       // e.g. New, Good, Fair
                "description TEXT, " +
                "price_per_day DECIMAL(10,2) NOT NULL, " +
                "location VARCHAR(150), " +            // pickup area / locality
                "available_from VARCHAR(10) NOT NULL, " +  // YYYY-MM-DD
                "available_to VARCHAR(10) NOT NULL, " +
                "status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE', " + // AVAILABLE, PAUSED, UNAVAILABLE, REMOVED
                "created_at " + now + " NOT NULL)",

            "CREATE TABLE IF NOT EXISTS cycle_photos (" +
                "id " + pk + ", " +
                "cycle_id BIGINT NOT NULL REFERENCES cycles(id) ON DELETE CASCADE, " +
                "filename VARCHAR(100) NOT NULL, " +
                "created_at " + now + " NOT NULL)",

            "CREATE TABLE IF NOT EXISTS bookings (" +
                "id " + pk + ", " +
                "cycle_id BIGINT NOT NULL REFERENCES cycles(id), " +   // no cascade: keep history if listing is removed (BR-10)
                "renter_id BIGINT NOT NULL REFERENCES users(id), " +
                "start_date VARCHAR(10) NOT NULL, " +
                "end_date VARCHAR(10) NOT NULL, " +
                "start_time VARCHAR(5), " +   // HH:MM pickup time (NULL on pre-time bookings)
                "end_time VARCHAR(5), " +     // HH:MM return time
                "total_amount DECIMAL(10,2) NOT NULL, " +
                "status VARCHAR(20) NOT NULL DEFAULT 'PENDING', " +    // PENDING, ACCEPTED, REJECTED, CANCELLED, ACTIVE, COMPLETED
                "pickup_owner_at " + now + ", " +
                "pickup_renter_at " + now + ", " +
                "return_owner_at " + now + ", " +
                "return_renter_at " + now + ", " +
                "created_at " + now + " NOT NULL, " +
                "updated_at " + now + " NOT NULL)",

            "CREATE TABLE IF NOT EXISTS payments (" +
                "id " + pk + ", " +
                "booking_id BIGINT NOT NULL REFERENCES bookings(id), " +
                "amount DECIMAL(10,2) NOT NULL, " +
                "method VARCHAR(50), " +               // e.g. UPI, Cash, Card
                "transaction_reference VARCHAR(100), " +
                "status VARCHAR(20) NOT NULL DEFAULT 'RECORDED', " +
                "paid_at " + now + " NOT NULL)",

            "CREATE TABLE IF NOT EXISTS reviews (" +
                "id " + pk + ", " +
                "booking_id BIGINT NOT NULL REFERENCES bookings(id), " +
                "reviewer_id BIGINT NOT NULL REFERENCES users(id), " +
                "reviewed_user_id BIGINT NOT NULL REFERENCES users(id), " +
                "rating INT NOT NULL, " +              // 1 to 5
                "comment TEXT, " +
                "created_at " + now + " NOT NULL, " +
                "UNIQUE (booking_id, reviewer_id))",   // one review per person per booking

            "CREATE TABLE IF NOT EXISTS notifications (" +
                "id " + pk + ", " +
                "user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE, " +
                "type VARCHAR(40) NOT NULL, " +
                "message VARCHAR(500) NOT NULL, " +
                "read_status INT NOT NULL DEFAULT 0, " +
                "created_at " + now + " NOT NULL)",

            "CREATE TABLE IF NOT EXISTS issue_reports (" +
                "id " + pk + ", " +
                "booking_id BIGINT REFERENCES bookings(id), " +
                "reporter_id BIGINT NOT NULL REFERENCES users(id), " +
                "description TEXT NOT NULL, " +
                "status VARCHAR(20) NOT NULL DEFAULT 'OPEN', " +
                "created_at " + now + " NOT NULL)"
        };

        try (Connection c = getConnection(); Statement st = c.createStatement()) {
            for (String sql : statements) {
                st.execute(sql);
            }
            // Migrations for databases created before pickup/return times existed.
            for (String alter : new String[] {
                    "ALTER TABLE bookings ADD COLUMN start_time VARCHAR(5)",
                    "ALTER TABLE bookings ADD COLUMN end_time VARCHAR(5)"}) {
                try {
                    st.execute(alter);
                } catch (SQLException ignored) {
                    // column already exists - nothing to do
                }
            }
        } catch (SQLException e) {
            throw new ExceptionInInitializerError("Could not initialise the database: " + e.getMessage()
                + "\nCheck DB_URL / DB_USER / DB_PASSWORD (see README). Current DB_URL=" + URL);
        }
    }

    /** Runs a SELECT and returns each row as a map of column name to value. */
    public static List<Map<String, Object>> query(String sql, Object... params) {
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                int cols = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= cols; i++) {
                        row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new ApiException(500, "Database error: " + e.getMessage());
        }
    }

    /** Like query(), but expects at most one row. Returns null when there is none. */
    public static Map<String, Object> queryOne(String sql, Object... params) {
        List<Map<String, Object>> rows = query(sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Runs an INSERT/UPDATE/DELETE. Returns the generated id for INSERTs, else the row count. */
    public static long execute(String sql, Object... params) {
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, params);
            int count = ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys != null && keys.next()) {
                    return keys.getLong(1);
                }
            }
            return count;
        } catch (SQLException e) {
            throw new ApiException(500, "Database error: " + e.getMessage());
        }
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }
}
