package powerconnect;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public class WebServer {

    private static final String[] STATUSES = {"Reported", "Assigned", "In Progress", "Resolved"};
    private static final int CLUSTER_THRESHOLD = 3;
    private static final int ESCALATION_MINUTES = 1440;   // 24 hours
    private static final int MAX_BODY = 2_500_000;
    private static final String OK = "{\"ok\":true}";
    private static final String COOKIE = "pc_session";
    private static final long SESSION_MS = 8L * 60 * 60 * 1000;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<String, long[]> ATTEMPTS = new ConcurrentHashMap<>();   // {failures, lockedUntil}
    private static final Map<String, List<String[]>> CHATS = new ConcurrentHashMap<>();

    static final String REPORT_SELECT =
            "SELECT r.id, r.category, r.problem_type AS problem, r.location, r.description, "
            + "s.name AS status, r.created_at, r.photo, r.resident_confirmed AS confirmed, "
            + "COALESCE(w.name, '') AS worker, COALESCE(w.phone, '') AS worker_phone, "
            + "(SELECT COUNT(*) FROM report_confirmations c WHERE c.report_id = r.id) AS confirmations "
            + "FROM reports r JOIN statuses s ON r.status_id = s.id "
            + "LEFT JOIN report_assignments a ON a.report_id = r.id "
            + "LEFT JOIN workers w ON w.id = a.worker_id";

    private static class Session {
        final Accounts.Account user;
        final long expires;

        Session(Accounts.Account user, long expires) {
            this.user = user;
            this.expires = expires;
        }
    }

    private static class Scope {
        final String sql;
        final Object[] params;

        Scope(String sql, Object... params) {
            this.sql = sql;
            this.params = params;
        }
    }

    private static class ApiException extends Exception {
        final int status;

        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public static void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", WebServer::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
    }

    // ---------- Request handling ----------

    private static void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/") || path.equals("/index.html")) {
                serveIndex(ex);
                return;
            }
            if (path.startsWith("/photos/")) {
                servePhoto(ex, currentUser(ex));
                return;
            }
            if (!path.startsWith("/api/")) {
                send(ex, 404, "text/plain; charset=utf-8", "Not found");
                return;
            }
            String method = ex.getRequestMethod();
            if (method.equals("POST")
                    && !"PowerConnect".equals(ex.getRequestHeaders().getFirst("X-Requested-With"))) {
                throw new ApiException(403, "Blocked request.");
            }
            Map<String, String> form = readForm(ex);
            Accounts.Account user = currentUser(ex);
            send(ex, 200, "application/json; charset=utf-8", route(ex, method, path, form, user));
        } catch (ApiException e) {
            send(ex, e.status, "application/json; charset=utf-8", "{\"error\":" + q(e.getMessage()) + "}");
        } catch (IllegalArgumentException e) {
            send(ex, 400, "application/json; charset=utf-8", "{\"error\":" + q(e.getMessage()) + "}");
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, "application/json; charset=utf-8", "{\"error\":\"Server error.\"}");
        }
    }

    private static String route(HttpExchange ex, String method, String path, Map<String, String> f,
                                Accounts.Account user) throws Exception {
        String[] p = path.split("/");   // "", "api", name, ...
        String name = p.length > 2 ? p[2] : "";
        boolean get = method.equals("GET");
        boolean post = method.equals("POST");

        // Public
        if (get && name.equals("session")) {
            return sessionJson(user);
        }
        if (post && name.equals("login")) {
            return login(ex, f);
        }
        if (post && name.equals("register")) {
            return register(ex, f);
        }

        // Everything else needs a login
        if (user == null) {
            throw new ApiException(401, "Please log in.");
        }
        if (post && name.equals("logout")) {
            return logout(ex);
        }
        if (post && name.equals("password")) {
            return changeOwnPassword(user, f);
        }
        if (name.equals("notifications")) {
            if (get) {
                return notifications(user);
            }
            if (post) {
                return markRead(user);
            }
        }

        if (name.equals("reports")) {
            if (get && p.length == 3) {
                return reportList(user);
            }
            if (post && p.length == 3) {
                return createReport(user, f);
            }
            if (get && p.length == 4) {
                return reportDetail(user, parseId(p[3]));
            }
            if (post && p.length == 5) {
                int id = parseId(p[3]);
                switch (p[4]) {
                    case "status":
                        return setStatus(user, id, f);
                    case "messages":
                        return addMessage(user, id, f);
                    case "feedback":
                        return addFeedback(user, id, f);
                    case "assign":
                        return assignWorker(user, id, f);
                    case "stage":
                        return stage(user, id, f);
                    case "confirm":
                        return confirmFix(user, id, f);
                    default:
                        break;
                }
            }
        }
        if (post && name.equals("check")) {
            return checkDuplicate(user, f);
        }
        if (post && name.equals("link")) {
            return linkReport(user, f);
        }
        if (get && name.equals("dashboard")) {
            return dashboard(user);
        }
        if (get && name.equals("directory")) {
            return directory(user);
        }
        if (post && name.equals("officials")) {
            return addOfficial(user, f);
        }
        if (post && name.equals("workers")) {
            return addWorker(user, f);
        }
        if (post && name.equals("demo")) {
            return loadDemo(user);
        }
        if (name.equals("accounts")) {
            if (post && p.length == 3) {
                return createLogin(user, f);
            }
            if (post && p.length == 5 && p[4].equals("password")) {
                return resetPassword(user, parseId(p[3]), f);
            }
        }
        if (post && name.equals("chat")) {
            return chat(user, f);
        }
        throw new ApiException(404, "Unknown endpoint.");
    }

    // ---------- Accounts and sessions ----------

    private static String sessionJson(Accounts.Account u) throws SQLException {
        if (u == null) {
            return "{\"loggedIn\":false}";
        }
        return "{\"loggedIn\":true,\"user\":" + userJson(u) + "}";
    }

    private static String userJson(Accounts.Account u) throws SQLException {
        String suburb = "";
        if (u.role.equals("councillor") && u.officialId != null) {
            String[] row = firstRow("SELECT suburb FROM officials WHERE id = ?", u.officialId);
            if (row != null) {
                suburb = row[0];
            }
        }
        return "{\"id\":" + u.id + ",\"username\":" + q(u.username) + ",\"role\":" + q(u.role)
                + ",\"name\":" + q(u.name) + ",\"suburb\":" + q(suburb) + "}";
    }

    private static String login(HttpExchange ex, Map<String, String> f) throws Exception {
        String username = clean(f.get("username"), 40).toLowerCase();
        String password = f.get("password") == null ? "" : f.get("password");
        long now = System.currentTimeMillis();
        if (ATTEMPTS.size() > 10000) {
            ATTEMPTS.clear();
        }
        long[] att = ATTEMPTS.computeIfAbsent(username, k -> new long[2]);
        synchronized (att) {
            if (att[1] > now) {
                throw new ApiException(429, "Too many attempts. Try again in a minute.");
            }
        }
        Accounts.Account acc = Accounts.authenticate(username, password);
        synchronized (att) {
            if (acc == null) {
                att[0]++;
                if (att[0] >= 5) {
                    att[1] = now + 60_000;
                    att[0] = 0;
                }
                throw new ApiException(401, "Wrong username or password.");
            }
            att[0] = 0;
        }
        startSession(ex, acc);
        return sessionJson(acc);
    }

    private static String register(HttpExchange ex, Map<String, String> f) throws Exception {
        String name = required(f, "name", 60);
        Accounts.Account acc = Accounts.create(f.get("username"), f.get("password"),
                "resident", name, null, null);
        startSession(ex, acc);
        return sessionJson(acc);
    }

    private static String logout(HttpExchange ex) {
        String token = cookie(ex, COOKIE);
        if (token != null) {
            SESSIONS.remove(token);
        }
        ex.getResponseHeaders().add("Set-Cookie",
                COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
        return OK;
    }

    private static void startSession(HttpExchange ex, Accounts.Account acc) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        SESSIONS.put(token, new Session(acc, System.currentTimeMillis() + SESSION_MS));
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token
                + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + (SESSION_MS / 1000));
    }

    private static Accounts.Account currentUser(HttpExchange ex) {
        String token = cookie(ex, COOKIE);
        if (token == null) {
            return null;
        }
        Session s = SESSIONS.get(token);
        if (s == null) {
            return null;
        }
        if (s.expires < System.currentTimeMillis()) {
            SESSIONS.remove(token);
            return null;
        }
        return s.user;
    }

    private static String cookie(HttpExchange ex, String name) {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers == null) {
            return null;
        }
        for (String h : headers) {
            for (String part : h.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals(name)) {
                    return kv[1];
                }
            }
        }
        return null;
    }

    private static String changeOwnPassword(Accounts.Account u, Map<String, String> f) throws Exception {
        String current = f.get("current") == null ? "" : f.get("current");
        if (Accounts.authenticate(u.username, current) == null) {
            throw new ApiException(403, "Your current password is wrong.");
        }
        Accounts.setPassword(u.id, f.get("password"));
        return OK;
    }

    private static String createLogin(Accounts.Account u, Map<String, String> f) throws Exception {
        need(u, "admin");
        String role = clean(f.get("role"), 12);
        int ref = parseId(clean(f.get("ref_id"), 9));
        String table;
        String column;
        if (role.equals("councillor")) {
            table = "officials";
            column = "official_id";
        } else if (role.equals("worker")) {
            table = "workers";
            column = "worker_id";
        } else {
            throw new ApiException(400, "Choose councillor or worker.");
        }
        String[] row = firstRow("SELECT name FROM " + table + " WHERE id = ?", ref);
        if (row == null) {
            throw new ApiException(404, "That person was not found.");
        }
        if (number("SELECT COUNT(*) FROM accounts WHERE " + column + " = ?", ref) > 0) {
            throw new ApiException(409, "That person already has a login.");
        }
        Accounts.create(f.get("username"), f.get("password"), role, row[0],
                role.equals("councillor") ? ref : null, role.equals("worker") ? ref : null);
        return OK;
    }

    private static String resetPassword(Accounts.Account u, int accountId, Map<String, String> f)
            throws Exception {
        need(u, "admin");
        if (number("SELECT COUNT(*) FROM accounts WHERE id = ?", accountId) == 0) {
            throw new ApiException(404, "Account not found.");
        }
        Accounts.setPassword(accountId, f.get("password"));
        SESSIONS.values().removeIf(s -> s.user.id == accountId);
        return OK;
    }

    // ---------- Access control ----------

    private static void need(Accounts.Account u, String... roles) throws ApiException {
        for (String r : roles) {
            if (u.role.equals(r)) {
                return;
            }
        }
        throw new ApiException(403, "You do not have permission to do that.");
    }

    // The SQL condition (on alias r) limiting which reports this user may see.
    private static Scope scope(Accounts.Account u) throws SQLException {
        if (u.role.equals("admin")) {
            return new Scope("1=1");
        }
        if (u.role.equals("resident")) {
            return new Scope("(r.account_id = ? OR r.id IN "
                    + "(SELECT report_id FROM report_confirmations WHERE account_id = ?))", u.id, u.id);
        }
        if (u.role.equals("councillor")) {
            String[] row = u.officialId == null ? null
                    : firstRow("SELECT suburb FROM officials WHERE id = ?", u.officialId);
            if (row == null) {
                return new Scope("1=0");
            }
            return new Scope("LOWER(TRIM(r.location)) = LOWER(TRIM(?))", row[0]);
        }
        if (u.role.equals("worker")) {
            return new Scope("r.id IN (SELECT report_id FROM report_assignments WHERE worker_id = ?)",
                    u.workerId == null ? -1 : u.workerId);
        }
        return new Scope("1=0");
    }

    static boolean canView(Accounts.Account u, int id) {
        try {
            Scope sc = scope(u);
            return number("SELECT COUNT(*) FROM reports r WHERE r.id = ? AND (" + sc.sql + ")",
                    join(new Object[] {id}, sc.params)) > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    private static void requireVisible(Accounts.Account u, int id) throws ApiException {
        if (!canView(u, id)) {
            throw new ApiException(404, "Report not found.");
        }
    }

    private static String actorLabel(Accounts.Account u) {
        return u.role.equals("resident") ? "Resident" : u.name + " (" + u.role + ")";
    }

    // ---------- Reports ----------

    private static String reportList(Accounts.Account u) throws Exception {
        Scope sc = scope(u);
        return rows(REPORT_SELECT + " WHERE " + sc.sql + " ORDER BY r.id DESC", sc.params);
    }

    private static String reportDetail(Accounts.Account u, int id) throws Exception {
        requireVisible(u, id);
        String[] info = firstRow("SELECT category, location FROM reports WHERE id = ?", id);
        boolean mine = number("SELECT COUNT(*) FROM reports WHERE id = ? AND account_id = ?", id, u.id) > 0;
        return "{\"report\":" + rows(REPORT_SELECT + " WHERE r.id = ?", id)
                + ",\"mine\":" + mine
                + ",\"events\":" + rows("SELECT actor, event, created_at FROM report_events "
                        + "WHERE report_id = ? ORDER BY id", id)
                + ",\"messages\":" + rows("SELECT sender, body, sent_at FROM messages "
                        + "WHERE report_id = ? ORDER BY id", id)
                + ",\"feedback\":" + rows("SELECT rating, comment, submitted_at FROM feedback "
                        + "WHERE report_id = ?", id)
                + ",\"councillors\":" + councillors(info[1])
                + ",\"utilities\":" + utilities(info[0]) + "}";
    }

    private static String createReport(Accounts.Account u, Map<String, String> f) throws Exception {
        need(u, "resident");
        String category = required(f, "category", 40).toUpperCase().replace(" ", "_");
        String description = required(f, "description", 1000);
        String location = required(f, "location", 80);
        String problem = clean(f.get("problem"), 40);
        try {
            ServiceCategory.valueOf(category);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "Invalid category.");
        }
        String photo = savePhoto(f.get("photo"));
        int id = insert("INSERT INTO reports (category, problem_type, description, location, reporter, "
                + "account_id, photo, status_id) VALUES (?, ?, ?, ?, ?, ?, ?, "
                + "(SELECT id FROM statuses WHERE name = 'Reported'))",
                category, problem.isEmpty() ? null : problem, description, location, u.name, u.id, photo);
        event(id, "Resident", "Report submitted");
        notifyReport(id, u.id, "New " + category.replace("_", " ").toLowerCase()
                + " report in " + location + ".");
        return "{\"id\":" + id + ",\"councillors\":" + councillors(location)
                + ",\"utilities\":" + utilities(category) + "}";
    }

    private static String setStatus(Accounts.Account u, int id, Map<String, String> f) throws Exception {
        need(u, "admin", "councillor", "worker");
        requireVisible(u, id);
        String wanted = clean(f.get("status"), 20);
        String status = null;
        for (String s : STATUSES) {
            if (s.equalsIgnoreCase(wanted)) {
                status = s;
            }
        }
        if (status == null) {
            throw new ApiException(400, "Invalid status.");
        }
        if (u.role.equals("worker") && !(status.equals("In Progress") || status.equals("Resolved"))) {
            throw new ApiException(403, "Workers can set In Progress or Resolved.");
        }
        changeStatus(id, status, actorLabel(u));
        notifyReport(id, u.id, "Status changed to " + status + ".");
        return OK;
    }

    private static void changeStatus(int id, String status, String actor) throws SQLException {
        update("UPDATE reports SET status_id = (SELECT id FROM statuses WHERE name = ?), "
                + "resident_confirmed = NULL WHERE id = ?", status, id);
        update("INSERT INTO report_status_history (report_id, status_id, changed_by, notes) "
                + "VALUES (?, (SELECT id FROM statuses WHERE name = ?), 1, 'Status updated')", id, status);
        event(id, actor, "Status changed to " + status);
    }

    private static String stage(Accounts.Account u, int id, Map<String, String> f) throws Exception {
        need(u, "worker");
        requireVisible(u, id);
        String stage = clean(f.get("stage"), 12);
        String text;
        if (stage.equals("departed")) {
            text = "Field worker is on the way";
        } else if (stage.equals("arrived")) {
            text = "Field worker has arrived on site";
        } else {
            throw new ApiException(400, "Invalid stage.");
        }
        event(id, actorLabel(u), text);
        if (stage.equals("arrived")) {
            String[] st = firstRow("SELECT s.name FROM reports r JOIN statuses s "
                    + "ON r.status_id = s.id WHERE r.id = ?", id);
            if ("Assigned".equals(st[0]) || "Reported".equals(st[0])) {
                changeStatus(id, "In Progress", actorLabel(u));
            }
        }
        notifyReport(id, u.id, text + ".");
        return OK;
    }

    private static String confirmFix(Accounts.Account u, int id, Map<String, String> f) throws Exception {
        need(u, "resident");
        if (number("SELECT COUNT(*) FROM reports WHERE id = ? AND account_id = ?", id, u.id) == 0) {
            throw new ApiException(403, "Only the person who reported this can confirm the fix.");
        }
        String[] st = firstRow("SELECT s.name FROM reports r JOIN statuses s "
                + "ON r.status_id = s.id WHERE r.id = ?", id);
        if (!"Resolved".equals(st[0])) {
            throw new ApiException(400, "This report is not marked Resolved yet.");
        }
        String answer = clean(f.get("answer"), 3);
        if (answer.equals("yes")) {
            update("UPDATE reports SET resident_confirmed = 1 WHERE id = ?", id);
            event(id, "Resident", "Resident confirmed the problem is fixed");
            notifyReport(id, u.id, "The resident confirmed the problem is fixed.");
        } else if (answer.equals("no")) {
            changeStatus(id, "In Progress", "Resident");
            event(id, "Resident", "Resident says the problem is NOT fixed. Report reopened");
            notifyReport(id, u.id, "The resident says it is NOT fixed. The report was reopened.");
        } else {
            throw new ApiException(400, "Answer yes or no.");
        }
        return OK;
    }

    private static String addMessage(Accounts.Account u, int id, Map<String, String> f) throws Exception {
        requireVisible(u, id);
        String body = required(f, "body", 500);
        update("INSERT INTO messages (report_id, sender, body) VALUES (?, ?, ?)",
                id, u.name + " (" + u.role + ")", body);
        notifyReport(id, u.id, "New message from " + u.name + ".");
        return OK;
    }

    private static String addFeedback(Accounts.Account u, int id, Map<String, String> f) throws Exception {
        need(u, "resident");
        if (number("SELECT COUNT(*) FROM reports WHERE id = ? AND account_id = ?", id, u.id) == 0) {
            throw new ApiException(403, "Only the person who reported this can rate it.");
        }
        if (number("SELECT COUNT(*) FROM reports WHERE id = ? AND resident_confirmed = 1", id) == 0) {
            throw new ApiException(400, "Please confirm the problem is fixed before rating.");
        }
        int rating;
        try {
            rating = Integer.parseInt(clean(f.get("rating"), 3));
        } catch (NumberFormatException e) {
            rating = 0;
        }
        if (rating < 1 || rating > 5) {
            throw new ApiException(400, "Rating must be between 1 and 5.");
        }
        if (number("SELECT COUNT(*) FROM feedback WHERE report_id = ?", id) > 0) {
            throw new ApiException(409, "Feedback has already been submitted for this report.");
        }
        update("INSERT INTO feedback (report_id, rating, comment) VALUES (?, ?, ?)",
                id, rating, clean(f.get("comment"), 300));
        event(id, "Resident", "Resident rated the service " + rating + " out of 5");
        notifyReport(id, u.id, "The resident rated this job " + rating + " out of 5.");
        return OK;
    }

    private static String assignWorker(Accounts.Account u, int id, Map<String, String> f) throws Exception {
        need(u, "admin", "councillor");
        requireVisible(u, id);
        int workerId = parseId(clean(f.get("worker_id"), 9));
        String[] w = firstRow("SELECT name FROM workers WHERE id = ?", workerId);
        if (w == null) {
            throw new ApiException(404, "Worker not found.");
        }
        update("INSERT INTO report_assignments (report_id, worker_id) VALUES (?, ?) "
                + "ON CONFLICT(report_id) DO UPDATE SET worker_id = excluded.worker_id, "
                + "assigned_at = CURRENT_TIMESTAMP", id, workerId);
        event(id, actorLabel(u), "Assigned to " + w[0]);
        String[] st = firstRow("SELECT s.name FROM reports r JOIN statuses s "
                + "ON r.status_id = s.id WHERE r.id = ?", id);
        if ("Reported".equals(st[0])) {
            changeStatus(id, "Assigned", actorLabel(u));
        }
        notifyReport(id, u.id, "Field worker " + w[0] + " was assigned.");
        return OK;
    }

    // ---------- Notifications and the timeline ----------

    private static void event(int reportId, String actor, String text) throws SQLException {
        update("INSERT INTO report_events (report_id, actor, event) VALUES (?, ?, ?)",
                reportId, actor, text);
    }

    // Tells everyone connected to a report: the reporter, residents who confirmed it,
    // the suburb's councillor and the assigned worker. Never the person who acted.
    private static void notifyReport(int reportId, int exceptAccount, String text) throws SQLException {
        List<Integer> ids = intList("SELECT DISTINCT acc FROM ("
                + " SELECT account_id AS acc FROM reports WHERE id = ? "
                + " UNION ALL SELECT account_id FROM report_confirmations WHERE report_id = ? "
                + " UNION ALL SELECT a.id FROM accounts a JOIN officials o ON a.official_id = o.id "
                + "   JOIN reports r ON LOWER(TRIM(o.suburb)) = LOWER(TRIM(r.location)) "
                + "   WHERE a.role = 'councillor' AND r.id = ? "
                + " UNION ALL SELECT a.id FROM accounts a JOIN report_assignments x "
                + "   ON x.worker_id = a.worker_id WHERE x.report_id = ?) "
                + "WHERE acc IS NOT NULL AND acc <> ?", reportId, reportId, reportId, reportId, exceptAccount);
        for (int acc : ids) {
            update("INSERT INTO notifications (account_id, report_id, message) VALUES (?, ?, ?)",
                    acc, reportId, "PC-" + reportId + ": " + text);
        }
    }

    private static String notifications(Accounts.Account u) throws SQLException {
        return "{\"unread\":" + number("SELECT COUNT(*) FROM notifications WHERE account_id = ? "
                + "AND is_read = 0", u.id)
                + ",\"items\":" + rows("SELECT id, report_id, message, created_at, is_read FROM notifications "
                        + "WHERE account_id = ? ORDER BY id DESC LIMIT 30", u.id) + "}";
    }

    private static String markRead(Accounts.Account u) throws SQLException {
        update("UPDATE notifications SET is_read = 1 WHERE account_id = ?", u.id);
        return OK;
    }

    // ---------- AI: duplicate detection and chat ----------

    private static String checkDuplicate(Accounts.Account u, Map<String, String> f) throws Exception {
        need(u, "resident");
        String category = required(f, "category", 40);
        String description = required(f, "description", 1000);
        String location = required(f, "location", 80);
        DuplicateDetector.Match m = DuplicateDetector.check(category, description, location);
        if (m == null) {
            return "{\"duplicate\":false}";
        }
        return "{\"duplicate\":true,\"id\":" + m.reportId + ",\"location\":" + q(m.location)
                + ",\"description\":" + q(m.description) + ",\"reason\":" + q(m.reason) + "}";
    }

    private static String linkReport(Accounts.Account u, Map<String, String> f) throws Exception {
        need(u, "resident");
        int id = parseId(clean(f.get("id"), 9));
        if (number("SELECT COUNT(*) FROM reports WHERE id = ?", id) == 0) {
            throw new ApiException(404, "Report not found.");
        }
        if (number("SELECT COUNT(*) FROM reports WHERE id = ? AND account_id = ?", id, u.id) > 0) {
            throw new ApiException(409, "That is already your report.");
        }
        if (number("SELECT COUNT(*) FROM report_confirmations WHERE report_id = ? AND account_id = ?",
                id, u.id) > 0) {
            throw new ApiException(409, "You have already confirmed this report.");
        }
        update("INSERT INTO report_confirmations (report_id, reporter, description, account_id) "
                + "VALUES (?, ?, ?, ?)", id, u.name, clean(f.get("description"), 1000), u.id);
        int n = DuplicateDetector.countConfirmations(id);
        event(id, "Resident", "Another resident confirmed this problem");
        notifyReport(id, u.id, "Another resident confirmed this problem (" + n + " in total).");
        return "{\"confirmations\":" + n + "}";
    }

    private static String chat(Accounts.Account u, Map<String, String> f) throws Exception {
        String message = required(f, "message", 500);
        if (CHATS.size() > 500) {
            CHATS.clear();
        }
        List<String[]> history = CHATS.computeIfAbsent(u.id + ":" + clean(f.get("session"), 64),
                k -> new ArrayList<>());
        String[] out;
        synchronized (history) {
            out = WebChat.answer(history, message, u.role, id -> canView(u, id));
        }
        return "{\"reply\":" + q(out[0]) + ",\"smart\":" + out[1].equals("smart") + "}";
    }

    // ---------- Dashboard and directory ----------

    private static String dashboard(Accounts.Account u) throws Exception {
        need(u, "admin", "councillor");
        Scope sc = scope(u);
        Object[] p = sc.params;
        int total = number("SELECT COUNT(*) FROM reports r WHERE " + sc.sql, p);
        int resolved = number("SELECT COUNT(*) FROM reports r JOIN statuses s ON r.status_id = s.id "
                + "WHERE s.name = 'Resolved' AND " + sc.sql, p);
        String escalationSql = "SELECT id, category, location, status, idle FROM ("
                + " SELECT r.id AS id, r.category AS category, r.location AS location, s.name AS status, "
                + " CAST((strftime('%s','now') - strftime('%s', COALESCE("
                + "   (SELECT MAX(h.changed_at) FROM report_status_history h WHERE h.report_id = r.id), "
                + "   r.created_at))) / 60 AS INTEGER) AS idle "
                + " FROM reports r JOIN statuses s ON r.status_id = s.id "
                + " WHERE s.name <> 'Resolved' AND " + sc.sql + ") WHERE idle >= ? ORDER BY idle DESC";
        return "{\"total\":" + total + ",\"open\":" + (total - resolved) + ",\"resolved\":" + resolved
                + ",\"threshold\":" + CLUSTER_THRESHOLD
                + ",\"escalationMinutes\":" + ESCALATION_MINUTES
                + ",\"rating\":" + rows("SELECT ROUND(AVG(f.rating), 1) AS average, COUNT(f.id) AS n "
                        + "FROM feedback f JOIN reports r ON r.id = f.report_id WHERE " + sc.sql, p)
                + ",\"avgMinutes\":" + rows("SELECT ROUND(AVG((julianday(x.t) - julianday(r.created_at)) * 1440), 0) "
                        + "AS minutes FROM reports r JOIN statuses cur ON r.status_id = cur.id "
                        + "JOIN (SELECT h.report_id AS rid, MAX(h.changed_at) AS t FROM report_status_history h "
                        + "JOIN statuses hs ON h.status_id = hs.id WHERE hs.name = 'Resolved' "
                        + "GROUP BY h.report_id) x ON x.rid = r.id "
                        + "WHERE cur.name = 'Resolved' AND " + sc.sql, p)
                + ",\"duplicates\":" + number("SELECT COUNT(*) FROM report_confirmations c "
                        + "JOIN reports r ON r.id = c.report_id WHERE " + sc.sql, p)
                + ",\"byDay\":" + rows("SELECT substr(r.created_at, 1, 10) AS label, COUNT(*) AS n "
                        + "FROM reports r WHERE " + sc.sql + " AND r.created_at >= date('now', '-6 days') "
                        + "GROUP BY 1 ORDER BY 1", p)
                + ",\"byStatus\":" + rows("SELECT s.name AS label, COUNT(r.id) AS n FROM statuses s "
                        + "LEFT JOIN reports r ON r.status_id = s.id AND " + sc.sql
                        + " GROUP BY s.id ORDER BY s.id", p)
                + ",\"byCategory\":" + rows("SELECT r.category AS label, COUNT(*) AS n FROM reports r "
                        + "WHERE " + sc.sql + " GROUP BY r.category ORDER BY n DESC", p)
                + ",\"byArea\":" + rows("SELECT LOWER(TRIM(r.location)) AS label, COUNT(*) AS n "
                        + "FROM reports r WHERE " + sc.sql
                        + " GROUP BY LOWER(TRIM(r.location)) ORDER BY n DESC", p)
                + ",\"clusters\":" + clusters(sc)
                + ",\"escalations\":" + rows(escalationSql, join(p, new Object[] {ESCALATION_MINUTES})) + "}";
    }

    // Uses the team's ClusterIntelligenceAnalyzer on the open reports this user may see.
    private static String clusters(Scope sc) throws SQLException {
        List<Report> open = new ArrayList<>();
        String sql = "SELECT r.id, r.category, r.description, r.location FROM reports r "
                + "JOIN statuses s ON r.status_id = s.id WHERE s.name <> 'Resolved' AND " + sc.sql;
        try (Connection c = DBConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, sc.params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        open.add(new Report("PC-" + rs.getInt(1),
                                new ServiceCategory1(ServiceCategory.valueOf(rs.getString(2))),
                                rs.getString(3), rs.getString(4)));
                    } catch (IllegalArgumentException e) {
                        // skip a report with an unknown category
                    }
                }
            }
        }
        List<ReportCluster> list = new ClusterIntelligenceAnalyzer(CLUSTER_THRESHOLD)
                .analyzeAndFlagClusters(open);
        list.sort((a, b) -> b.getReportCount() - a.getReportCount());
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            ReportCluster c = list.get(i);
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"category\":").append(q(String.valueOf(c.getCategory())))
                    .append(",\"suburb\":").append(q(String.valueOf(c.getLocation())))
                    .append(",\"n\":").append(c.getReportCount())
                    .append(",\"flagged\":").append(c.isFlagged()).append("}");
        }
        return sb.append("]").toString();
    }

    private static String directory(Accounts.Account u) throws Exception {
        boolean admin = u.role.equals("admin");
        boolean staff = admin || u.role.equals("councillor");
        String officials = admin
                ? rows("SELECT o.id, o.name, o.title, o.suburb, o.phone, o.hotline, "
                        + "(SELECT username FROM accounts a WHERE a.official_id = o.id) AS login "
                        + "FROM officials o ORDER BY o.suburb, o.name")
                : rows("SELECT id, name, title, suburb, phone, hotline FROM officials ORDER BY suburb, name");
        String workers = !staff ? "[]" : admin
                ? rows("SELECT w.id, w.name, w.specialty, w.phone, "
                        + "(SELECT username FROM accounts a WHERE a.worker_id = w.id) AS login "
                        + "FROM workers w ORDER BY w.id")
                : rows("SELECT id, name, specialty, phone FROM workers ORDER BY id");
        String accounts = admin
                ? rows("SELECT id, username, role, display_name AS name, created_at FROM accounts ORDER BY id")
                : "[]";
        return "{\"officials\":" + officials + ",\"workers\":" + workers
                + ",\"utilities\":" + rows("SELECT name, services, hotline, sms, whatsapp, email, notes "
                        + "FROM utilities ORDER BY id")
                + ",\"accounts\":" + accounts + "}";
    }

    private static String addOfficial(Accounts.Account u, Map<String, String> f) throws Exception {
        need(u, "admin");
        String name = required(f, "name", 80);
        String suburb = required(f, "suburb", 80);
        String title = clean(f.get("title"), 40);
        if (title.isEmpty()) {
            title = "Councillor";
        }
        String phone = clean(f.get("phone"), 40);
        String hotline = clean(f.get("hotline"), 60);
        if (phone.isEmpty() && hotline.isEmpty()) {
            throw new ApiException(400, "Add at least a phone number or a hotline.");
        }
        String username = clean(f.get("username"), 40);
        if (!username.isEmpty()) {
            Accounts.validate(username, f.get("password"), "councillor");
        }
        int id = insert("INSERT INTO officials (name, title, suburb, phone, hotline) VALUES (?, ?, ?, ?, ?)",
                name, title, suburb, phone, hotline);
        if (!username.isEmpty()) {
            Accounts.create(username, f.get("password"), "councillor", name, id, null);
        }
        return OK;
    }

    private static String addWorker(Accounts.Account u, Map<String, String> f) throws Exception {
        need(u, "admin");
        String name = required(f, "name", 80);
        String phone = required(f, "phone", 40);
        String username = clean(f.get("username"), 40);
        if (!username.isEmpty()) {
            Accounts.validate(username, f.get("password"), "worker");
        }
        int id = insert("INSERT INTO workers (name, specialty, phone) VALUES (?, ?, ?)",
                name, clean(f.get("specialty"), 40), phone);
        if (!username.isEmpty()) {
            Accounts.create(username, f.get("password"), "worker", name, null, id);
        }
        return OK;
    }

    private static String councillors(String suburb) throws SQLException {
        return rows("SELECT name, title, phone, hotline FROM officials "
                + "WHERE LOWER(TRIM(suburb)) = LOWER(TRIM(?))", suburb);
    }

    private static String utilities(String category) throws SQLException {
        return rows("SELECT name, hotline, sms, whatsapp, email, notes FROM utilities "
                + "WHERE ',' || services || ',' LIKE '%,' || ? || ',%' ORDER BY id", category);
    }

    // ---------- Demo data (admin only) ----------

    private static String loadDemo(Accounts.Account u) throws Exception {
        need(u, "admin");
        String pw = "PowerConnect1";
        String[] o = firstRow("SELECT id FROM officials WHERE name = 'Demo Councillor'");
        int officialId = o != null ? Integer.parseInt(o[0])
                : insert("INSERT INTO officials (name, title, suburb, phone, hotline) "
                        + "VALUES ('Demo Councillor', 'Councillor', 'Chelstone', 'DEMO ONLY', 'DEMO ONLY')");
        String[] w = firstRow("SELECT id FROM workers WHERE name = 'Mike Banda (demo)'");
        int workerId = w != null ? Integer.parseInt(w[0])
                : insert("INSERT INTO workers (name, specialty, phone) "
                        + "VALUES ('Mike Banda (demo)', 'Water, Electricity, Sanitation', 'DEMO ONLY')");
        demoAccount("cllr.chelstone", pw, "councillor", "Demo Councillor", officialId, null);
        demoAccount("worker.mike", pw, "worker", "Mike Banda (demo)", null, workerId);
        for (String n : new String[] {"paul", "betty", "joy", "sean", "pauline"}) {
            demoAccount(n, pw, "resident", Character.toUpperCase(n.charAt(0)) + n.substring(1), null, null);
        }
        int owner = demoAccount("demo.resident", pw, "resident", "Demo Resident", null, null);
        if (number("SELECT COUNT(*) FROM reports WHERE account_id = ?", owner) == 0) {
            seedReport(owner, "Overflowing manhole at Chelstone clinic");
            seedReport(owner, "Rubbish blocking the drain at Chelstone market");
        }
        return "{\"password\":" + q(pw) + "}";
    }

    private static int demoAccount(String username, String pw, String role, String name,
                                   Integer officialId, Integer workerId) throws Exception {
        String[] row = firstRow("SELECT id FROM accounts WHERE username = ?", username);
        if (row != null) {
            return Integer.parseInt(row[0]);
        }
        return Accounts.create(username, pw, role, name, officialId, workerId).id;
    }

    private static void seedReport(int owner, String text) throws SQLException {
        int id = insert("INSERT INTO reports (category, description, location, reporter, account_id, status_id) "
                + "VALUES ('SANITATION', ?, 'Chelstone', 'Demo Resident', ?, "
                + "(SELECT id FROM statuses WHERE name = 'Reported'))", text, owner);
        event(id, "Resident", "Report submitted");
    }

    // ---------- Photos ----------

    private static String savePhoto(String dataUrl) throws Exception {
        if (dataUrl == null || dataUrl.isEmpty()) {
            return null;
        }
        int comma = dataUrl.indexOf(";base64,");
        if (comma < 0 || !dataUrl.startsWith("data:image/")) {
            throw new ApiException(400, "The photo must be an image.");
        }
        String kind = dataUrl.substring("data:image/".length(), comma);
        String ext = kind.equals("jpeg") ? "jpg" : (kind.equals("png") || kind.equals("webp")) ? kind : null;
        if (ext == null) {
            throw new ApiException(400, "The photo must be a JPEG, PNG or WebP image.");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(dataUrl.substring(comma + 8));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "The photo could not be read.");
        }
        if (bytes.length > 1_500_000) {
            throw new ApiException(400, "The photo is too large.");
        }
        byte[] id = new byte[8];
        RANDOM.nextBytes(id);
        String name = HexFormat.of().formatHex(id) + "." + ext;
        Files.createDirectories(Path.of("uploads"));
        Files.write(Path.of("uploads", name), bytes);
        return name;
    }

    private static void servePhoto(HttpExchange ex, Accounts.Account user) throws Exception {
        if (user == null) {
            throw new ApiException(401, "Please log in.");
        }
        String name = ex.getRequestURI().getPath().substring("/photos/".length());
        if (!name.matches("[a-f0-9]{16}\\.(jpg|png|webp)")) {
            throw new ApiException(404, "Not found.");
        }
        String[] row = firstRow("SELECT id FROM reports WHERE photo = ?", name);
        if (row == null || !canView(user, Integer.parseInt(row[0]))) {
            throw new ApiException(404, "Not found.");
        }
        Path file = Path.of("uploads", name);
        if (!Files.exists(file)) {
            throw new ApiException(404, "Not found.");
        }
        String type = name.endsWith(".png") ? "image/png" : name.endsWith(".webp") ? "image/webp" : "image/jpeg";
        sendBytes(ex, 200, type, "private, max-age=3600", Files.readAllBytes(file));
    }

    // ---------- Database helpers ----------

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            if (params[i] == null) {
                ps.setNull(i + 1, Types.NULL);
            } else {
                ps.setObject(i + 1, params[i]);
            }
        }
    }

    private static Object[] join(Object[] a, Object[] b) {
        Object[] out = new Object[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // Runs a query and returns the rows as a JSON array of objects.
    static String rows(String sql, Object... params) throws SQLException {
        StringBuilder sb = new StringBuilder("[");
        try (Connection c = DBConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int cols = md.getColumnCount();
                boolean first = true;
                while (rs.next()) {
                    if (!first) {
                        sb.append(",");
                    }
                    first = false;
                    sb.append("{");
                    for (int i = 1; i <= cols; i++) {
                        if (i > 1) {
                            sb.append(",");
                        }
                        sb.append(q(md.getColumnLabel(i))).append(":").append(value(rs.getObject(i)));
                    }
                    sb.append("}");
                }
            }
        }
        return sb.append("]").toString();
    }

    private static String value(Object o) {
        if (o == null) {
            return "null";
        }
        if (o instanceof Number) {
            return o.toString();
        }
        return q(o.toString());
    }

    static String[] firstRow(String sql, Object... params) throws SQLException {
        try (Connection c = DBConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                int n = rs.getMetaData().getColumnCount();
                String[] row = new String[n];
                for (int i = 0; i < n; i++) {
                    row[i] = rs.getString(i + 1);
                }
                return row;
            }
        }
    }

    private static List<Integer> intList(String sql, Object... params) throws SQLException {
        List<Integer> list = new ArrayList<>();
        try (Connection c = DBConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getInt(1));
                }
            }
        }
        return list;
    }

    static int number(String sql, Object... params) throws SQLException {
        String[] row = firstRow(sql, params);
        return (row == null || row[0] == null) ? 0 : Integer.parseInt(row[0]);
    }

    private static void update(String sql, Object... params) throws SQLException {
        try (Connection c = DBConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            ps.executeUpdate();
        }
    }

    private static int insert(String sql, Object... params) throws SQLException {
        try (Connection c = DBConnection.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, params);
                ps.executeUpdate();
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    // ---------- Small utilities ----------

    private static int parseId(String s) throws ApiException {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new ApiException(400, "Invalid number.");
        }
    }

    private static String clean(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String required(Map<String, String> f, String key, int max) throws ApiException {
        String v = clean(f.get(key), max);
        if (v.isEmpty()) {
            throw new ApiException(400, "Please fill in: " + key + ".");
        }
        return v;
    }

    private static String q(String s) {
        return "\"" + esc(s) + "\"";
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static Map<String, String> readForm(HttpExchange ex) throws IOException, ApiException {
        byte[] data = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (data.length > MAX_BODY) {
            throw new ApiException(413, "That request is too large.");
        }
        String body = new String(data, StandardCharsets.UTF_8);
        Map<String, String> map = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int i = pair.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            map.put(k, v);
        }
        return map;
    }

    private static void serveIndex(HttpExchange ex) throws IOException {
        Path file = Path.of("web", "index.html");
        if (!Files.exists(file)) {
            send(ex, 500, "text/plain; charset=utf-8",
                    "web/index.html not found. Looked in: " + file.toAbsolutePath());
            return;
        }
        send(ex, 200, "text/html; charset=utf-8", Files.readString(file));
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        ex.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; script-src 'self' 'unsafe-inline'; "
                + "style-src 'self' 'unsafe-inline'; img-src 'self' data:");
        sendBytes(ex, status, type, "no-store", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(HttpExchange ex, int status, String type, String cache, byte[] bytes)
            throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", cache);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}