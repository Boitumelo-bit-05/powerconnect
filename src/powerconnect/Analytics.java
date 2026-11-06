package powerconnect;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Analytics {

    // A suburb with this many open reports is marked as a hotspot.
    private static final int HOTSPOT = 3;

    // ---------- Console dashboard ----------

    public static void showDashboard() {
        try {
            int total = number("SELECT COUNT(*) FROM reports");
            if (total == 0) {
                System.out.println("No reports yet, so there is nothing to chart.");
                return;
            }
            int resolved = number("SELECT COUNT(*) FROM reports r JOIN statuses s "
                    + "ON r.status_id = s.id WHERE s.name = 'Resolved'");

            System.out.println("\n=== ANALYTICS DASHBOARD ===");
            System.out.println("Total: " + total + "   Open: " + (total - resolved)
                    + "   Resolved: " + resolved);
            System.out.println(averageRating());

            printChart("Reports by status", byStatus());
            printChart("Reports by category", byCategory());
            printChart("Reports by area (suburb)", byArea());
        } catch (SQLException e) {
            System.out.println("Could not build dashboard: " + e.getMessage());
        }
    }

    // ---------- Map view: open reports by suburb ----------

    public static void showOpenReportsMap() {
        try {
            Map<String, Map<String, Integer>> map = openBySuburb();
            System.out.println("\n=== OPEN REPORTS BY SUBURB ===");
            if (map.isEmpty()) {
                System.out.println("No open reports.");
                return;
            }

            List<Map.Entry<String, Map<String, Integer>>> rows = new ArrayList<>(map.entrySet());
            rows.sort((a, b) -> total(b.getValue()) - total(a.getValue()));
            int max = total(rows.get(0).getValue());

            for (Map.Entry<String, Map<String, Integer>> row : rows) {
                int n = total(row.getValue());
                System.out.println(String.format("%-18s %-26s %d%s", row.getKey(),
                        bar(n, max), n, n >= HOTSPOT ? "  <-- HOTSPOT" : ""));
                System.out.println("    " + breakdown(row.getValue()));
            }
        } catch (SQLException e) {
            System.out.println("Could not build map view: " + e.getMessage());
        }
    }

    // ---------- HTML export (charts + suburb map) ----------

    public static void exportHtml() {
        try {
            int total = number("SELECT COUNT(*) FROM reports");
            int resolved = number("SELECT COUNT(*) FROM reports r JOIN statuses s "
                    + "ON r.status_id = s.id WHERE s.name = 'Resolved'");
            Map<String, Map<String, Integer>> open = openBySuburb();

            StringBuilder h = new StringBuilder();
            h.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">");
            h.append("<title>PowerConnect Dashboard</title><style>");
            h.append("body{font-family:Arial,sans-serif;margin:24px;background:#f4f6f8;color:#222}");
            h.append("h2{margin-top:32px}");
            h.append(".cards{display:flex;gap:12px;flex-wrap:wrap}");
            h.append(".card{background:#fff;border-radius:8px;padding:14px 22px;");
            h.append("box-shadow:0 1px 3px rgba(0,0,0,.15)}");
            h.append(".card b{display:block;font-size:28px}");
            h.append(".row{display:flex;align-items:center;margin:6px 0}");
            h.append(".label{width:170px}");
            h.append(".track{flex:1;max-width:480px;background:#dde3ea;border-radius:4px}");
            h.append(".bar{background:#1f6feb;height:20px;border-radius:4px}");
            h.append(".num{margin-left:10px;font-weight:bold}");
            h.append(".tiles{display:flex;flex-wrap:wrap;gap:12px}");
            h.append(".tile{width:180px;padding:14px;border-radius:8px;color:#fff}");
            h.append(".tile b{display:block;font-size:26px}");
            h.append(".tile small{display:block;margin-top:6px}");
            h.append("</style></head><body>");

            h.append("<h1>PowerConnect Analytics</h1><div class=\"cards\">");
            h.append("<div class=\"card\"><b>").append(total).append("</b>Total reports</div>");
            h.append("<div class=\"card\"><b>").append(total - resolved).append("</b>Open</div>");
            h.append("<div class=\"card\"><b>").append(resolved).append("</b>Resolved</div>");
            h.append("</div><p>").append(esc(averageRating())).append("</p>");

            htmlChart(h, "Reports by status", byStatus());
            htmlChart(h, "Reports by category", byCategory());
            htmlChart(h, "Reports by area (suburb)", byArea());

            h.append("<h2>Open reports by suburb</h2>");
            if (open.isEmpty()) {
                h.append("<p>No open reports.</p>");
            } else {
                h.append("<div class=\"tiles\">");
                List<Map.Entry<String, Map<String, Integer>>> rows = new ArrayList<>(open.entrySet());
                rows.sort((a, b) -> total(b.getValue()) - total(a.getValue()));
                for (Map.Entry<String, Map<String, Integer>> row : rows) {
                    int n = total(row.getValue());
                    String colour = n >= HOTSPOT ? "#c62828" : (n == HOTSPOT - 1 ? "#ef6c00" : "#2e7d32");
                    h.append("<div class=\"tile\" style=\"background:").append(colour).append("\">");
                    h.append("<b>").append(n).append("</b>").append(esc(row.getKey()));
                    h.append("<small>").append(esc(breakdown(row.getValue()))).append("</small>");
                    if (n >= HOTSPOT) {
                        h.append("<small>HOTSPOT</small>");
                    }
                    h.append("</div>");
                }
                h.append("</div>");
            }
            h.append("</body></html>");

            Path file = Path.of("dashboard.html").toAbsolutePath();
            Files.writeString(file, h.toString());
            System.out.println("Dashboard saved to: " + file);
            System.out.println("Open that file in your browser to view it.");
        } catch (IOException | SQLException e) {
            System.out.println("Could not export dashboard: " + e.getMessage());
        }
    }

    // ---------- Queries ----------

    private static Map<String, Integer> byStatus() throws SQLException {
        return counts("SELECT s.name, COUNT(r.id) FROM statuses s "
                + "LEFT JOIN reports r ON r.status_id = s.id GROUP BY s.id ORDER BY s.id");
    }

    private static Map<String, Integer> byCategory() throws SQLException {
        return counts("SELECT category, COUNT(*) AS n FROM reports "
                + "GROUP BY category ORDER BY n DESC");
    }

    private static Map<String, Integer> byArea() throws SQLException {
        return counts("SELECT LOWER(TRIM(location)), COUNT(*) AS n FROM reports "
                + "GROUP BY LOWER(TRIM(location)) ORDER BY n DESC");
    }

    private static Map<String, Map<String, Integer>> openBySuburb() throws SQLException {
        Map<String, Map<String, Integer>> map = new LinkedHashMap<>();
        String sql = "SELECT LOWER(TRIM(r.location)), r.category, COUNT(*) "
                + "FROM reports r JOIN statuses s ON r.status_id = s.id "
                + "WHERE s.name <> 'Resolved' "
                + "GROUP BY LOWER(TRIM(r.location)), r.category";
        try (Connection c = DBConnection.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                map.computeIfAbsent(title(rs.getString(1)), k -> new LinkedHashMap<>())
                        .merge(title(rs.getString(2)), rs.getInt(3), Integer::sum);
            }
        }
        return map;
    }

    private static Map<String, Integer> counts(String sql) throws SQLException {
        Map<String, Integer> map = new LinkedHashMap<>();
        try (Connection c = DBConnection.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                map.merge(title(rs.getString(1)), rs.getInt(2), Integer::sum);
            }
        }
        return map;
    }

    private static int number(String sql) throws SQLException {
        try (Connection c = DBConnection.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static String averageRating() throws SQLException {
        try (Connection c = DBConnection.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT AVG(rating), COUNT(*) FROM feedback")) {
            if (rs.next() && rs.getInt(2) > 0) {
                return String.format("Average rating: %.1f / 5 (%d rating(s))",
                        rs.getDouble(1), rs.getInt(2));
            }
        }
        return "Average rating: no ratings yet";
    }

    // ---------- Helpers ----------

    private static void printChart(String heading, Map<String, Integer> data) {
        System.out.println("\n" + heading);
        int max = 1;
        for (int v : data.values()) {
            max = Math.max(max, v);
        }
        for (Map.Entry<String, Integer> e : data.entrySet()) {
            System.out.println(String.format("  %-20s | %-26s %d",
                    e.getKey(), bar(e.getValue(), max), e.getValue()));
        }
    }

    private static void htmlChart(StringBuilder h, String heading, Map<String, Integer> data) {
        h.append("<h2>").append(esc(heading)).append("</h2>");
        int max = 1;
        for (int v : data.values()) {
            max = Math.max(max, v);
        }
        for (Map.Entry<String, Integer> e : data.entrySet()) {
            int pct = e.getValue() * 100 / max;
            h.append("<div class=\"row\"><div class=\"label\">").append(esc(e.getKey()))
                    .append("</div><div class=\"track\"><div class=\"bar\" style=\"width:")
                    .append(pct).append("%\"></div></div><div class=\"num\">")
                    .append(e.getValue()).append("</div></div>");
        }
    }

    private static String bar(int n, int max) {
        if (n <= 0) {
            return "";
        }
        return "#".repeat(Math.max(1, n * 25 / max));
    }

    private static int total(Map<String, Integer> map) {
        int sum = 0;
        for (int v : map.values()) {
            sum += v;
        }
        return sum;
    }

    private static String breakdown(Map<String, Integer> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append(": ").append(e.getValue());
        }
        return sb.toString();
    }

    private static String title(String s) {
        if (s == null || s.isBlank()) {
            return "Unknown";
        }
        String[] words = s.replace("_", " ").trim().toLowerCase().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
