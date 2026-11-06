package powerconnect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class ReportManager {

    private static final String[] STATUSES = {"Reported", "Assigned", "In Progress", "Resolved"};

    // A report with no progress for this many minutes is escalated.
    // 1440 = 24 hours. Set to 1 to test.
    private static final int ESCALATION_MINUTES = 1440;

    private final List<Report> reports = new ArrayList<>();
    private int nextId = 1;

    public ReportManager() {
        loadReportsFromDatabase();
    }

    // ---------- Cluster Intelligence (open reports only) ----------

    public void runClusterAnalysis() {
        List<Report> open = new ArrayList<>();
        for (Report r : reports) {
            if (!"Resolved".equalsIgnoreCase(r.getStatus())) {
                open.add(r);
            }
        }

        ClusterIntelligenceAnalyzer analyzer = new ClusterIntelligenceAnalyzer(3);
        List<ReportCluster> clusters = analyzer.analyzeAndFlagClusters(open);

        System.out.println("=== CLUSTER INTELLIGENCE ===");
        if (clusters.isEmpty()) {
            System.out.println("No open reports.");
        }
        for (ReportCluster c : clusters) {
            System.out.println(c.getCategory() + " in " + c.getLocation()
                    + ": " + c.getReportCount() + " report(s)"
                    + (c.isFlagged() ? "  <-- ALERT" : ""));
            if (c.isFlagged()) {
                Directory.printCouncillorFor(c.getLocation());
            }
        }
    }

    // ---------- Reports ----------

    public void submitReport(String category, String description, String location, String reporter) {

        if (category == null || category.trim().isEmpty()
                || description == null || description.trim().isEmpty()
                || location == null || location.trim().isEmpty()) {
            System.out.println("Please complete all report fields.");
            return;
        }

        String who = (reporter == null || reporter.isBlank()) ? "Anonymous" : reporter.trim();
        String categoryKey = category.trim().toUpperCase().replace(" ", "_");

        ServiceCategory1 cat;
        try {
            cat = new ServiceCategory1(ServiceCategory.valueOf(categoryKey));
        } catch (IllegalArgumentException e) {
            System.out.println("Invalid category. Choose: water, electricity, "
                    + "waste management, sanitation or road maintenance.");
            return;
        }

        Report report = new Report("PC-" + nextId, cat, description, location);

        reports.add(report);
        saveReportToDatabase(nextId, categoryKey, description, location, who);
        nextId++;

        System.out.println("Report submitted successfully!");
        System.out.println("Your reference is: " + report.getReportId());
        Directory.printCouncillorFor(location);
        Utilities.printFor(categoryKey);
    }

    public void viewReports() {
        if (reports.isEmpty()) {
            System.out.println("No reports have been submitted.");
            return;
        }

        for (Report report : reports) {
            report.displayReport();
        }
    }

    public void updateReportStatus(String reportId, String newStatus) {
        String status = normalizeStatus(newStatus);
        if (status == null) {
            System.out.println("Invalid status. Choose: Assigned, In Progress or Resolved.");
            return;
        }

        Report report = findReport(reportId);
        if (report == null) {
            System.out.println("Report not found: " + reportId);
            return;
        }

        report.updateStatus(status);
        saveStatusChange(dbId(report), status);
        System.out.println("Current status: " + report.getStatus());
    }

    // ---------- In-app messaging (tied to a report) ----------

    public void sendMessage(String reportId, String sender, String text) {
        Report report = findReport(reportId);
        if (report == null) {
            System.out.println("Report not found: " + reportId);
            return;
        }
        if (sender == null || sender.trim().isEmpty() || text == null || text.trim().isEmpty()) {
            System.out.println("Name and message cannot be empty.");
            return;
        }

        String sql = "INSERT INTO messages (report_id, sender, body) VALUES (?, ?, ?)";
        try (Connection c = DBConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, dbId(report));
            ps.setString(2, sender.trim());
            ps.setString(3, text.trim());
            ps.executeUpdate();
            System.out.println("Message sent on " + report.getReportId() + ".");
        } catch (SQLException e) {
            System.out.println("Could not send message: " + e.getMessage());
        }
    }

    public void viewMessages(String reportId) {
        Report report = findReport(reportId);
        if (report == null) {
            System.out.println("Report not found: " + reportId);
            return;
        }

        String sql = "SELECT sender, body, sent_at FROM messages WHERE report_id = ? ORDER BY id";
        try (Connection c = DBConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, dbId(report));
            try (ResultSet rs = ps.executeQuery()) {
                System.out.println("--- Messages for " + report.getReportId() + " ---");
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    System.out.println("[" + rs.getString("sent_at") + "] "
                            + rs.getString("sender") + ": " + rs.getString("body"));
                }
                if (!any) {
                    System.out.println("No messages yet.");
                }
            }
        } catch (SQLException e) {
            System.out.println("Could not load messages: " + e.getMessage());
        }
    }

    // ---------- Feedback / rating (after completion) ----------

    public void submitFeedback(String reportId, int rating, String comment) {
        Report report = findReport(reportId);
        if (report == null) {
            System.out.println("Report not found: " + reportId);
            return;
        }
        if (!"Resolved".equalsIgnoreCase(report.getStatus())) {
            System.out.println("Feedback can only be given once the report is Resolved.");
            return;
        }
        if (rating < 1 || rating > 5) {
            System.out.println("Rating must be between 1 and 5.");
            return;
        }

        int id = dbId(report);
        try (Connection c = DBConnection.getConnection()) {
            try (PreparedStatement check = c.prepareStatement(
                    "SELECT 1 FROM feedback WHERE report_id = ?")) {
                check.setInt(1, id);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        System.out.println("Feedback has already been submitted for this report.");
                        return;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO feedback (report_id, rating, comment) VALUES (?, ?, ?)")) {
                ps.setInt(1, id);
                ps.setInt(2, rating);
                ps.setString(3, comment == null ? "" : comment.trim());
                ps.executeUpdate();
            }
            System.out.println("Thank you! " + report.getReportId() + " rated "
                    + "*".repeat(rating) + " (" + rating + "/5).");
        } catch (SQLException e) {
            System.out.println("Could not save feedback: " + e.getMessage());
        }
    }

    // ---------- Auto-escalation ----------

    public void runAutoEscalation() {
        String sql = "SELECT id, category, location, status, idle FROM ("
                + " SELECT r.id AS id, r.category AS category, r.location AS location, "
                + " s.name AS status, "
                + " CAST((strftime('%s','now') - strftime('%s', COALESCE("
                + "   (SELECT MAX(h.changed_at) FROM report_status_history h WHERE h.report_id = r.id), "
                + "   r.created_at))) / 60 AS INTEGER) AS idle "
                + " FROM reports r JOIN statuses s ON r.status_id = s.id "
                + " WHERE s.name <> 'Resolved') "
                + "WHERE idle >= ? ORDER BY idle DESC";

        try (Connection c = DBConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, ESCALATION_MINUTES);
            try (ResultSet rs = ps.executeQuery()) {
                System.out.println("=== AUTO-ESCALATION (no progress for "
                        + ESCALATION_MINUTES + "+ minutes) ===");
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    System.out.println("ESCALATED: PC-" + rs.getInt("id")
                            + " (" + rs.getString("category") + ", " + rs.getString("location") + ")"
                            + " - " + rs.getString("status")
                            + ", idle " + rs.getInt("idle") + " min");
                }
                if (!any) {
                    System.out.println("No reports need escalation.");
                }
            }
        } catch (SQLException e) {
            System.out.println("Could not check escalation: " + e.getMessage());
        }
    }

    // ---------- Helpers ----------

    private Report findReport(String reportId) {
        if (reportId == null) {
            return null;
        }
        for (Report report : reports) {
            if (report.getReportId().equalsIgnoreCase(reportId.trim())) {
                return report;
            }
        }
        return null;
    }

    private int dbId(Report report) {
        return Integer.parseInt(report.getReportId().substring(3));
    }

    private String normalizeStatus(String input) {
        if (input == null) {
            return null;
        }
        for (String s : STATUSES) {
            if (s.equalsIgnoreCase(input.trim())) {
                return s;
            }
        }
        return null;
    }

    // ---------- Database ----------

    private void saveReportToDatabase(int id, String category, String description,
                                      String location, String reporter) {
        String sql = "INSERT INTO reports (id, category, description, location, reporter, status_id) "
                + "VALUES (?, ?, ?, ?, ?, (SELECT id FROM statuses WHERE name = 'Reported'))";
        try (Connection c = DBConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.setString(2, category);
            ps.setString(3, description);
            ps.setString(4, location);
            ps.setString(5, reporter);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("Could not save report: " + e.getMessage());
        }
    }

    private void saveStatusChange(int id, String status) {
        try (Connection c = DBConnection.getConnection()) {
            try (PreparedStatement up = c.prepareStatement(
                    "UPDATE reports SET status_id = "
                    + "(SELECT id FROM statuses WHERE name = ?) WHERE id = ?")) {
                up.setString(1, status);
                up.setInt(2, id);
                up.executeUpdate();
            }
            try (PreparedStatement h = c.prepareStatement(
                    "INSERT INTO report_status_history (report_id, status_id, changed_by, notes) "
                    + "VALUES (?, (SELECT id FROM statuses WHERE name = ?), 1, 'Status updated')")) {
                h.setInt(1, id);
                h.setString(2, status);
                h.executeUpdate();
            }
        } catch (SQLException e) {
            System.out.println("Could not save status change: " + e.getMessage());
        }
    }

    private void loadReportsFromDatabase() {
        String sql = "SELECT r.id, r.category, r.description, r.location, s.name AS status "
                + "FROM reports r JOIN statuses s ON r.status_id = s.id ORDER BY r.id";
        try (Connection c = DBConnection.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            int maxId = 0;
            while (rs.next()) {
                int id = rs.getInt("id");
                ServiceCategory1 cat = new ServiceCategory1(
                        ServiceCategory.valueOf(rs.getString("category")));
                Report report = new Report("PC-" + id, cat,
                        rs.getString("description"), rs.getString("location"));
                report.setStatusQuietly(rs.getString("status"));
                reports.add(report);
                if (id > maxId) {
                    maxId = id;
                }
            }
            nextId = maxId + 1;
        } catch (SQLException | IllegalArgumentException e) {
            System.out.println("Could not load reports: " + e.getMessage());
        }
    }
}