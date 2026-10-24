package lms;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Library Management System.
 * Plain Java: built-in HTTP server + JDBC (SQLite). No frameworks.
 */
public class Main {
    static final int PORT = 8080;
    static final int LOAN_DAYS = 14;        // how long a book can be kept
    static final int MAX_LOANS = 3;         // books per member at a time
    static final int FINE_PER_DAY = 2;      // rupees per day late

    static final Path WEB = Paths.get("web").toAbsolutePath().normalize();
    static final Map<String, String> sessions = new ConcurrentHashMap<>(); // token -> username

    static class ApiException extends RuntimeException {
        final int status;
        ApiException(int status, String message) { super(message); this.status = status; }
    }

    public static void main(String[] args) throws Exception {
        Db.init();
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", Main::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Library Management System is open at http://localhost:" + PORT);
        System.out.println("Sign in with  admin / library123");
    }

    // ---------- request plumbing ----------

    static void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) api(ex, path);
            else serveStatic(ex, path);
        } catch (ApiException e) {
            sendJson(ex, e.status, Json.obj("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(ex, 500, Json.obj("error", "The server hit an unexpected problem. Check the console for details."));
        } finally {
            ex.close();
        }
    }

    static Map<String, String> params(HttpExchange ex) throws IOException {
        Map<String, String> m = new HashMap<>();
        parseInto(m, ex.getRequestURI().getRawQuery());
        if (ex.getRequestMethod().equals("POST"))
            parseInto(m, new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        return m;
    }

    static void parseInto(Map<String, String> m, String s) {
        if (s == null || s.isEmpty()) return;
        for (String pair : s.split("&")) {
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            String v = i < 0 ? "" : pair.substring(i + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
    }

    static String sessionToken(HttpExchange ex) {
        String cookies = ex.getRequestHeaders().getFirst("Cookie");
        if (cookies == null) return null;
        for (String part : cookies.split(";")) {
            String p = part.trim();
            if (p.startsWith("lms_session=")) return p.substring("lms_session=".length());
        }
        return null;
    }

    static String sessionUser(HttpExchange ex) {
        String t = sessionToken(ex);
        return t == null ? null : sessions.get(t);
    }

    static void sendJson(HttpExchange ex, int status, String body) throws IOException {
        sendBytes(ex, status, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    static void sendBytes(HttpExchange ex, int status, String type, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(status, data.length == 0 ? -1 : data.length);
        if (data.length > 0) ex.getResponseBody().write(data);
    }

    static void serveStatic(HttpExchange ex, String path) throws IOException {
        if (path.equals("/")) path = "/index.html";
        Path file = WEB.resolve(path.substring(1)).normalize();
        if (!file.startsWith(WEB) || !Files.isRegularFile(file)) {
            sendBytes(ex, 404, "text/plain; charset=utf-8", "Page not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String name = file.getFileName().toString();
        String type = name.endsWith(".html") ? "text/html; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".js") ? "text/javascript; charset=utf-8"
                : name.endsWith(".svg") ? "image/svg+xml"
                : "application/octet-stream";
        sendBytes(ex, 200, type, Files.readAllBytes(file));
    }

    // ---------- validation helpers ----------

    static String need(Map<String, String> p, String key, String label, int max) {
        String v = p.getOrDefault(key, "").trim();
        if (v.isEmpty()) throw new ApiException(400, label + " is required.");
        if (v.length() > max) throw new ApiException(400, label + " is too long (max " + max + " characters).");
        return v;
    }

    static long id(Map<String, String> p, String key) {
        try { return Long.parseLong(p.getOrDefault(key, "")); }
        catch (NumberFormatException e) { throw new ApiException(400, "Missing or invalid " + key + "."); }
    }

    static int copies(Map<String, String> p) {
        try {
            int n = Integer.parseInt(p.getOrDefault("copies", "").trim());
            if (n < 1 || n > 99) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) { throw new ApiException(400, "Copies must be a number from 1 to 99."); }
    }

    // ---------- API ----------

    static void api(HttpExchange ex, String path) throws Exception {
        String route = ex.getRequestMethod() + " " + path;
        Map<String, String> p = params(ex);

        if (route.equals("POST /api/login")) {
            String u = p.getOrDefault("username", "").trim();
            if (!Db.checkLogin(u, p.getOrDefault("password", "")))
                throw new ApiException(401, "Username or password is wrong.");
            String token = UUID.randomUUID().toString();
            sessions.put(token, u);
            ex.getResponseHeaders().add("Set-Cookie", "lms_session=" + token + "; Path=/; HttpOnly; SameSite=Lax");
            sendJson(ex, 200, Json.obj("user", u));
            return;
        }

        String user = sessionUser(ex);
        if (user == null) throw new ApiException(401, "Please sign in.");

        switch (route) {
            case "GET /api/me" -> sendJson(ex, 200, Json.obj("user", user));
            case "POST /api/logout" -> {
                String t = sessionToken(ex);
                if (t != null) sessions.remove(t);
                ex.getResponseHeaders().add("Set-Cookie", "lms_session=; Path=/; Max-Age=0");
                sendJson(ex, 200, Json.obj("ok", true));
            }
            case "GET /api/stats" -> stats(ex);
            case "GET /api/books" -> listBooks(ex, p);
            case "POST /api/books" -> saveBook(ex, p, false);
            case "POST /api/books/update" -> saveBook(ex, p, true);
            case "POST /api/books/delete" -> deleteBook(ex, p);
            case "GET /api/members" -> listMembers(ex, p);
            case "POST /api/members" -> addMember(ex, p);
            case "POST /api/members/delete" -> deleteMember(ex, p);
            case "GET /api/loans" -> listLoans(ex, p);
            case "POST /api/issue" -> issue(ex, p);
            case "POST /api/return" -> giveBack(ex, p);
            default -> throw new ApiException(404, "Unknown request.");
        }
    }

    static void stats(HttpExchange ex) throws Exception {
        String today = LocalDate.now().toString();
        try (Connection c = Db.open()) {
            sendJson(ex, 200, Json.obj(
                    "titles", Db.count(c, "SELECT COUNT(*) FROM books"),
                    "copies", Db.count(c, "SELECT COALESCE(SUM(copies),0) FROM books"),
                    "out", Db.count(c, "SELECT COUNT(*) FROM loans WHERE returned_on IS NULL"),
                    "members", Db.count(c, "SELECT COUNT(*) FROM members"),
                    "overdue", Db.count(c, "SELECT COUNT(*) FROM loans WHERE returned_on IS NULL AND due_on < ?", today),
                    "finesCollected", Db.count(c, "SELECT COALESCE(SUM(fine),0) FROM loans"),
                    "loanDays", LOAN_DAYS, "maxLoans", MAX_LOANS, "finePerDay", FINE_PER_DAY));
        }
    }

    static void listBooks(HttpExchange ex, Map<String, String> p) throws Exception {
        String q = "%" + p.getOrDefault("q", "").trim().toLowerCase() + "%";
        sendJson(ex, 200, Json.list(Db.query(
                "SELECT id, title, author, category, copies, available FROM books " +
                "WHERE lower(title) LIKE ? OR lower(author) LIKE ? OR lower(category) LIKE ? " +
                "ORDER BY category, title", q, q, q)));
    }

    static void saveBook(HttpExchange ex, Map<String, String> p, boolean isUpdate) throws Exception {
        String title = need(p, "title", "Title", 150);
        String author = need(p, "author", "Author", 120);
        String category = need(p, "category", "Category", 40);
        int copies = copies(p);
        try (Connection c = Db.open()) {
            if (!isUpdate) {
                long id = Db.insert(c, "INSERT INTO books (title, author, category, copies, available) VALUES (?, ?, ?, ?, ?)",
                        title, author, category, copies, copies);
                sendJson(ex, 201, Json.obj("id", id));
                return;
            }
            long id = id(p, "id");
            List<Map<String, Object>> row = Db.query(c, "SELECT copies, available FROM books WHERE id = ?", id);
            if (row.isEmpty()) throw new ApiException(404, "That book no longer exists.");
            int borrowed = ((Number) row.get(0).get("copies")).intValue() - ((Number) row.get(0).get("available")).intValue();
            if (copies < borrowed)
                throw new ApiException(409, borrowed + " copies are currently issued, so total copies can't go below " + borrowed + ".");
            Db.update(c, "UPDATE books SET title = ?, author = ?, category = ?, copies = ?, available = ? WHERE id = ?",
                    title, author, category, copies, copies - borrowed, id);
            sendJson(ex, 200, Json.obj("id", id));
        }
    }

    static void deleteBook(HttpExchange ex, Map<String, String> p) throws Exception {
        long id = id(p, "id");
        try (Connection c = Db.open()) {
            if (Db.count(c, "SELECT COUNT(*) FROM loans WHERE book_id = ? AND returned_on IS NULL", id) > 0)
                throw new ApiException(409, "This book is issued to someone. Return it before deleting.");
            Db.update(c, "DELETE FROM books WHERE id = ?", id);
            sendJson(ex, 200, Json.obj("ok", true));
        }
    }

    static void listMembers(HttpExchange ex, Map<String, String> p) throws Exception {
        String q = "%" + p.getOrDefault("q", "").trim().toLowerCase() + "%";
        sendJson(ex, 200, Json.list(Db.query(
                "SELECT m.id, m.name, m.email, m.phone, m.joined_on, " +
                "(SELECT COUNT(*) FROM loans l WHERE l.member_id = m.id AND l.returned_on IS NULL) AS borrowing " +
                "FROM members m WHERE lower(m.name) LIKE ? OR lower(COALESCE(m.email,'')) LIKE ? ORDER BY m.name", q, q)));
    }

    static void addMember(HttpExchange ex, Map<String, String> p) throws Exception {
        String name = need(p, "name", "Name", 100);
        String email = p.getOrDefault("email", "").trim();
        String phone = p.getOrDefault("phone", "").trim();
        if (!email.isEmpty() && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
            throw new ApiException(400, "That email address doesn't look right.");
        if (!phone.isEmpty() && !phone.matches("^[0-9+\\- ]{7,15}$"))
            throw new ApiException(400, "Phone number should have 7 to 15 digits.");
        try (Connection c = Db.open()) {
            long id = Db.insert(c, "INSERT INTO members (name, email, phone, joined_on) VALUES (?, ?, ?, ?)",
                    name, email.isEmpty() ? null : email, phone.isEmpty() ? null : phone, LocalDate.now().toString());
            sendJson(ex, 201, Json.obj("id", id));
        }
    }

    static void deleteMember(HttpExchange ex, Map<String, String> p) throws Exception {
        long id = id(p, "id");
        try (Connection c = Db.open()) {
            if (Db.count(c, "SELECT COUNT(*) FROM loans WHERE member_id = ? AND returned_on IS NULL", id) > 0)
                throw new ApiException(409, "This member still has books. Collect them before removing the member.");
            Db.update(c, "DELETE FROM members WHERE id = ?", id);
            sendJson(ex, 200, Json.obj("ok", true));
        }
    }

    static void listLoans(HttpExchange ex, Map<String, String> p) throws Exception {
        String status = p.getOrDefault("status", "active");
        LocalDate today = LocalDate.now();
        String sql = "SELECT l.id, l.book_id, l.member_id, b.title, b.author, m.name AS member, " +
                "l.issued_on, l.due_on, l.returned_on, l.fine FROM loans l " +
                "JOIN books b ON b.id = l.book_id JOIN members m ON m.id = l.member_id ";
        List<Object> args = new ArrayList<>();
        String order;
        switch (status) {
            case "active" -> { sql += "WHERE l.returned_on IS NULL "; order = "ORDER BY l.due_on, l.id"; }
            case "overdue" -> { sql += "WHERE l.returned_on IS NULL AND l.due_on < ? "; args.add(today.toString()); order = "ORDER BY l.due_on, l.id"; }
            case "returned" -> { sql += "WHERE l.returned_on IS NOT NULL "; order = "ORDER BY l.returned_on DESC, l.id DESC"; }
            default -> order = "ORDER BY COALESCE(l.returned_on, l.issued_on) DESC, l.id DESC";
        }
        int limit = 200;
        try { limit = Math.max(1, Math.min(200, Integer.parseInt(p.getOrDefault("limit", "200")))); } catch (NumberFormatException ignored) {}
        List<Map<String, Object>> rows = Db.query(sql + order + " LIMIT " + limit, args.toArray());
        for (Map<String, Object> r : rows) {
            if (r.get("returned_on") != null) { r.put("state", "returned"); r.put("daysLate", 0); continue; }
            long late = Math.max(0, ChronoUnit.DAYS.between(LocalDate.parse((String) r.get("due_on")), today));
            r.put("state", late > 0 ? "overdue" : "active");
            r.put("daysLate", late);
            r.put("fine", late * FINE_PER_DAY); // fine accrued so far
        }
        sendJson(ex, 200, Json.list(rows));
    }

    static void issue(HttpExchange ex, Map<String, String> p) throws Exception {
        long bookId = id(p, "book_id"), memberId = id(p, "member_id");
        LocalDate today = LocalDate.now(), due = today.plusDays(LOAN_DAYS);
        try (Connection c = Db.open()) {
            c.setAutoCommit(false);
            try {
                List<Map<String, Object>> book = Db.query(c, "SELECT title, available FROM books WHERE id = ?", bookId);
                List<Map<String, Object>> member = Db.query(c, "SELECT name FROM members WHERE id = ?", memberId);
                if (book.isEmpty()) throw new ApiException(404, "That book no longer exists.");
                if (member.isEmpty()) throw new ApiException(404, "That member no longer exists.");
                if (((Number) book.get(0).get("available")).intValue() < 1)
                    throw new ApiException(409, "All copies of this book are already issued.");
                if (Db.count(c, "SELECT COUNT(*) FROM loans WHERE member_id = ? AND book_id = ? AND returned_on IS NULL", memberId, bookId) > 0)
                    throw new ApiException(409, "This member already has a copy of this book.");
                if (Db.count(c, "SELECT COUNT(*) FROM loans WHERE member_id = ? AND returned_on IS NULL", memberId) >= MAX_LOANS)
                    throw new ApiException(409, "This member already has " + MAX_LOANS + " books. Ask them to return one first.");
                long loanId = Db.insert(c, "INSERT INTO loans (book_id, member_id, issued_on, due_on) VALUES (?, ?, ?, ?)",
                        bookId, memberId, today.toString(), due.toString());
                Db.update(c, "UPDATE books SET available = available - 1 WHERE id = ?", bookId);
                c.commit();
                sendJson(ex, 201, Json.obj("id", loanId, "title", book.get(0).get("title"),
                        "member", member.get(0).get("name"), "issued_on", today.toString(), "due_on", due.toString()));
            } catch (Exception e) { c.rollback(); throw e; }
        }
    }

    static void giveBack(HttpExchange ex, Map<String, String> p) throws Exception {
        long loanId = id(p, "loan_id");
        LocalDate today = LocalDate.now();
        try (Connection c = Db.open()) {
            c.setAutoCommit(false);
            try {
                List<Map<String, Object>> rows = Db.query(c,
                        "SELECT book_id, due_on, returned_on FROM loans WHERE id = ?", loanId);
                if (rows.isEmpty()) throw new ApiException(404, "That loan doesn't exist.");
                Map<String, Object> loan = rows.get(0);
                if (loan.get("returned_on") != null) throw new ApiException(409, "This book was already returned.");
                long late = Math.max(0, ChronoUnit.DAYS.between(LocalDate.parse((String) loan.get("due_on")), today));
                long fine = late * FINE_PER_DAY;
                Db.update(c, "UPDATE loans SET returned_on = ?, fine = ? WHERE id = ?", today.toString(), fine, loanId);
                Db.update(c, "UPDATE books SET available = available + 1 WHERE id = ?", loan.get("book_id"));
                c.commit();
                sendJson(ex, 200, Json.obj("fine", fine, "daysLate", late));
            } catch (Exception e) { c.rollback(); throw e; }
        }
    }
}
