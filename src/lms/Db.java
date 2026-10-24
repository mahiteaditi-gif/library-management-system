package lms;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

/** All JDBC plumbing: connections, schema, helpers and sample data. */
final class Db {
    private Db() {}

    static final String URL = "jdbc:sqlite:library.db";

    static Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(URL);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys = ON");
            s.execute("PRAGMA busy_timeout = 5000");
        }
        return c;
    }

    // ---------- helpers ----------

    private static void bind(PreparedStatement ps, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
    }

    static List<Map<String, Object>> query(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                    out.add(row);
                }
                return out;
            }
        }
    }

    static List<Map<String, Object>> query(String sql, Object... args) throws SQLException {
        try (Connection c = open()) { return query(c, sql, args); }
    }

    static int update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        }
    }

    static int update(String sql, Object... args) throws SQLException {
        try (Connection c = open()) { return update(c, sql, args); }
    }

    static long insert(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, args);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { return rs.next() ? rs.getLong(1) : -1; }
        }
    }

    static long count(Connection c, String sql, Object... args) throws SQLException {
        List<Map<String, Object>> r = query(c, sql, args);
        Object v = r.get(0).values().iterator().next();
        return v == null ? 0 : ((Number) v).longValue();
    }

    static String hash(String password) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(("granthalaya:" + password).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static boolean checkLogin(String user, String pass) throws SQLException {
        return !query("SELECT 1 FROM users WHERE username = ? AND password_hash = ?", user, hash(pass)).isEmpty();
    }

    // ---------- schema + sample data ----------

    static void init() throws SQLException {
        try (Connection c = open()) {
            update(c, "CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY, username TEXT UNIQUE NOT NULL, password_hash TEXT NOT NULL)");
            update(c, "CREATE TABLE IF NOT EXISTS books (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, author TEXT NOT NULL, category TEXT NOT NULL, copies INTEGER NOT NULL CHECK (copies >= 1), available INTEGER NOT NULL CHECK (available >= 0))");
            update(c, "CREATE TABLE IF NOT EXISTS members (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, email TEXT, phone TEXT, joined_on TEXT NOT NULL)");
            update(c, "CREATE TABLE IF NOT EXISTS loans (id INTEGER PRIMARY KEY AUTOINCREMENT, book_id INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE, member_id INTEGER NOT NULL REFERENCES members(id) ON DELETE CASCADE, issued_on TEXT NOT NULL, due_on TEXT NOT NULL, returned_on TEXT, fine INTEGER NOT NULL DEFAULT 0)");

            if (count(c, "SELECT COUNT(*) FROM users") == 0) {
                update(c, "INSERT INTO users (username, password_hash) VALUES (?, ?)", "admin", hash("library123"));
            }
            if (count(c, "SELECT COUNT(*) FROM books") == 0) seed(c);
        }
    }

    private static void seed(Connection c) throws SQLException {
        String[][] books = {
            {"Wings of Fire", "A. P. J. Abdul Kalam", "Biography", "4"},
            {"The Discovery of India", "Jawaharlal Nehru", "History", "2"},
            {"Sapiens", "Yuval Noah Harari", "History", "3"},
            {"Malgudi Days", "R. K. Narayan", "Fiction", "3"},
            {"The Guide", "R. K. Narayan", "Fiction", "2"},
            {"The Alchemist", "Paulo Coelho", "Fiction", "3"},
            {"Python Crash Course", "Eric Matthes", "Computer Science", "3"},
            {"Clean Code", "Robert C. Martin", "Computer Science", "2"},
            {"Introduction to Algorithms", "Cormen, Leiserson, Rivest, Stein", "Computer Science", "2"},
            {"Hands-On Machine Learning", "Aurélien Géron", "Data Science", "2"},
            {"Storytelling with Data", "Cole Nussbaumer Knaflic", "Data Science", "2"},
            {"Atomic Habits", "James Clear", "Self Help", "4"},
            {"Thinking, Fast and Slow", "Daniel Kahneman", "Psychology", "2"},
        };
        for (String[] b : books) {
            int n = Integer.parseInt(b[3]);
            update(c, "INSERT INTO books (title, author, category, copies, available) VALUES (?, ?, ?, ?, ?)", b[0], b[1], b[2], n, n);
        }
        LocalDate today = LocalDate.now();
        String[][] members = {
            {"Rohan Patil", "rohan.patil@example.com", "98220 10001", "120"},
            {"Sneha Kulkarni", "sneha.k@example.com", "98220 10002", "95"},
            {"Imran Shaikh", "imran.s@example.com", "98220 10003", "80"},
            {"Priya Deshmukh", "priya.d@example.com", "98220 10004", "60"},
            {"Karan Jadhav", "karan.j@example.com", "98220 10005", "45"},
            {"Neha Pawar", "neha.p@example.com", "98220 10006", "20"},
        };
        for (String[] m : members) {
            update(c, "INSERT INTO members (name, email, phone, joined_on) VALUES (?, ?, ?, ?)",
                    m[0], m[1], m[2], today.minusDays(Long.parseLong(m[3])).toString());
        }
        // book_id, member_id, issued days ago, due days ago (negative = in future), returned days ago (-999 = not returned), fine
        int[][] loans = {
            {1, 1, 5, -9, -999, 0},
            {7, 2, 20, 6, -999, 0},
            {8, 3, 18, 4, -999, 0},
            {12, 4, 3, -11, -999, 0},
            {3, 5, 30, 16, 12, 8},
            {10, 1, 9, -5, -999, 0},
            {4, 6, 40, 26, 30, 0},
        };
        for (int[] l : loans) {
            String returned = l[4] == -999 ? null : today.minusDays(l[4]).toString();
            update(c, "INSERT INTO loans (book_id, member_id, issued_on, due_on, returned_on, fine) VALUES (?, ?, ?, ?, ?, ?)",
                    l[0], l[1], today.minusDays(l[2]).toString(), today.minusDays(l[3]).toString(), returned, l[5]);
        }
        update(c, "UPDATE books SET available = copies - (SELECT COUNT(*) FROM loans WHERE loans.book_id = books.id AND returned_on IS NULL)");
    }
}
