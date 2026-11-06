package powerconnect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Scanner;

public class Directory {

    public static void menu(Scanner input) {
        int choice;
        do {
            System.out.println("\n--- COUNCILLORS, HOTLINES & WORKERS ---");
            System.out.println("1. List councillors and hotlines");
            System.out.println("2. List field workers");
            System.out.println("3. Add a councillor");
            System.out.println("4. Add a field worker");
            System.out.println("5. Councillor view: open reports in a suburb");
            System.out.println("0. Back");
            System.out.print("Choose an option: ");

            try {
                choice = Integer.parseInt(input.nextLine().trim());
            } catch (NumberFormatException e) {
                choice = -1;
            }

            switch (choice) {
                case 1 -> {
                    listOfficials();
                    Utilities.list();
                }
                case 2 -> listWorkers();
                case 3 -> addOfficial(input);
                case 4 -> addWorker(input);
                case 5 -> councillorView(input);
                case 0 -> System.out.println("Back to main menu.");
                default -> System.out.println("Invalid option.");
            }
        } while (choice != 0);
    }

    // ---------- Councillors ----------

    public static void listOfficials() {
        String sql = "SELECT name, title, suburb, phone, hotline FROM officials ORDER BY suburb, name";
        try (Connection c = DBConnection.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            System.out.println("--- Councillors ---");
            boolean any = false;
            while (rs.next()) {
                any = true;
                System.out.println(rs.getString("name") + " (" + rs.getString("title") + ") - "
                        + rs.getString("suburb"));
                System.out.println("    Hotline: " + nz(rs.getString("hotline"))
                        + "   Phone: " + nz(rs.getString("phone")));
            }
            if (!any) {
                System.out.println("No councillors added yet. Use option 3 to add one.");
            }
        } catch (SQLException e) {
            System.out.println("Could not list councillors: " + e.getMessage());
        }
    }

    private static void addOfficial(Scanner input) {
        System.out.print("Councillor name: ");
        String name = input.nextLine().trim();
        System.out.print("Title (press Enter for Councillor): ");
        String title = input.nextLine().trim();
        System.out.print("Suburb / ward they cover: ");
        String suburb = input.nextLine().trim();
        System.out.print("Phone number: ");
        String phone = input.nextLine().trim();
        System.out.print("Hotline: ");
        String hotline = input.nextLine().trim();

        if (name.isEmpty() || suburb.isEmpty() || (phone.isEmpty() && hotline.isEmpty())) {
            System.out.println("Name, suburb and at least one phone number or hotline are required.");
            return;
        }
        if (title.isEmpty()) {
            title = "Councillor";
        }

        String sql = "INSERT INTO officials (name, title, suburb, phone, hotline) VALUES (?, ?, ?, ?, ?)";
        try (Connection c = DBConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, title);
            ps.setString(3, suburb);
            ps.setString(4, phone);
            ps.setString(5, hotline);
            ps.executeUpdate();
            System.out.println(name + " added for " + suburb + ".");
        } catch (SQLException e) {
            System.out.println("Could not add councillor: " + e.getMessage());
        }
    }

    // Shows the councillor(s) for a suburb. Used after a report is submitted.
    public static void printCouncillorFor(String suburb) {
        String sql = "SELECT name, title, phone, hotline FROM officials "
                + "WHERE LOWER(TRIM(suburb)) = LOWER(TRIM(?))";
        try (Connection c = DBConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, suburb == null ? "" : suburb);
            try (ResultSet rs = ps.executeQuery()) {
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    System.out.println("Your councillor: " + rs.getString("name")
                            + " (" + rs.getString("title") + ") - Hotline: "
                            + nz(rs.getString("hotline")) + ", Phone: " + nz(rs.getString("phone")));
                }
                if (!any) {
                    System.out.println("No councillor is registered for " + suburb + " yet.");
                }
            }
        } catch (SQLException e) {
            System.out.println("Could not look up councillor: " + e.getMessage());
        }
    }

    // ---------- Workers ----------

    public static int listWorkers() {
        String sql = "SELECT id, name, specialty, phone FROM workers ORDER BY id";
        int count = 0;
        try (Connection c = DBConnection.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            System.out.println("--- Field workers ---");
            while (rs.next()) {
                count++;
                System.out.println("[" + rs.getInt("id") + "] " + rs.getString("name")
                        + " (" + nz(rs.getString("specialty")) + ") - Phone: "
                        + nz(rs.getString("phone")));
            }
            if (count == 0) {
                System.out.println("No field workers added yet. Use option 4 to add one.");
            }
        } catch (SQLException e) {
            System.out.println("Could not list workers: " + e.getMessage());
        }
        return count;
    }

    private static void addWorker(Scanner input) {
        System.out.print("Worker name: ");
        String name = input.nextLine().trim();
        System.out.print("Specialty (Electricity, Water, Sanitation, General): ");
        String specialty = input.nextLine().trim();
        System.out.print("Phone number: ");
        String phone = input.nextLine().trim();

        if (name.isEmpty() || phone.isEmpty()) {
            System.out.println("Name and phone number are required.");
            return;
        }

        String sql = "INSERT INTO workers (name, specialty, phone) VALUES (?, ?, ?)";
        try (Connection c = DBConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, specialty);
            ps.setString(3, phone);
            ps.executeUpdate();
            System.out.println(name + " added as a field worker.");
        } catch (SQLException e) {
            System.out.println("Could not add worker: " + e.getMessage());
        }
    }

    // Returns the report reference if the report was still "Reported"
    // (so Main can move it to "Assigned"), otherwise null.
    public static String assignWorker(Scanner input) {
        System.out.print("Report reference (e.g. PC-1): ");
        int reportId = parseId(input.nextLine().trim().toUpperCase());
        if (reportId < 0) {
            System.out.println("Invalid reference.");
            return null;
        }

        if (listWorkers() == 0) {
            return null;
        }
        System.out.print("Worker ID to assign: ");
        int workerId;
        try {
            workerId = Integer.parseInt(input.nextLine().trim());
        } catch (NumberFormatException e) {
            System.out.println("Invalid worker ID.");
            return null;
        }

        try (Connection c = DBConnection.getConnection()) {
            String status = null;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT s.name FROM reports r JOIN statuses s ON r.status_id = s.id WHERE r.id = ?")) {
                ps.setInt(1, reportId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        status = rs.getString(1);
                    }
                }
            }
            if (status == null) {
                System.out.println("Report not found: PC-" + reportId);
                return null;
            }

            String workerName = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT name FROM workers WHERE id = ?")) {
                ps.setInt(1, workerId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        workerName = rs.getString(1);
                    }
                }
            }
            if (workerName == null) {
                System.out.println("Worker not found: " + workerId);
                return null;
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO report_assignments (report_id, worker_id) VALUES (?, ?) "
                    + "ON CONFLICT(report_id) DO UPDATE SET worker_id = excluded.worker_id, "
                    + "assigned_at = CURRENT_TIMESTAMP")) {
                ps.setInt(1, reportId);
                ps.setInt(2, workerId);
                ps.executeUpdate();
            }

            System.out.println(workerName + " assigned to PC-" + reportId + ".");
            return status.equals("Reported") ? "PC-" + reportId : null;
        } catch (SQLException e) {
            System.out.println("Could not assign worker: " + e.getMessage());
            return null;
        }
    }

    // ---------- Contacts for one report ----------

    public static void showReportContacts(Scanner input) {
        System.out.print("Report reference (e.g. PC-1): ");
        int id = parseId(input.nextLine().trim().toUpperCase());
        if (id < 0) {
            System.out.println("Invalid reference.");
            return;
        }

        String sql = "SELECT r.category, r.location, s.name, w.name, w.phone "
                + "FROM reports r JOIN statuses s ON r.status_id = s.id "
                + "LEFT JOIN report_assignments a ON a.report_id = r.id "
                + "LEFT JOIN workers w ON w.id = a.worker_id WHERE r.id = ?";
        String category;
        String location;
        String status;
        String worker;
        String workerPhone;
        try (Connection c = DBConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    System.out.println("Report not found: PC-" + id);
                    return;
                }
                category = rs.getString(1);
                location = rs.getString(2);
                status = rs.getString(3);
                worker = rs.getString(4);
                workerPhone = rs.getString(5);
            }
        } catch (SQLException e) {
            System.out.println("Could not load contacts: " + e.getMessage());
            return;
        }

        System.out.println("\n--- Contacts for PC-" + id + " ---");
        System.out.println("Category: " + pretty(category) + "   Suburb: " + location
                + "   Status: " + status);
        if (worker == null) {
            System.out.println("Field worker: not assigned yet");
        } else {
            System.out.println("Field worker: " + worker + " - Phone: " + nz(workerPhone));
        }
        printCouncillorFor(location);
    }

    // ---------- Councillor view ----------

    private static void councillorView(Scanner input) {
        System.out.print("Suburb: ");
        String suburb = input.nextLine().trim();
        if (suburb.isEmpty()) {
            System.out.println("Please enter a suburb.");
            return;
        }

        System.out.println("\n--- Open reports in " + suburb + " ---");
        printCouncillorFor(suburb);

        String sql = "SELECT r.id, r.category, r.description, s.name, w.name, w.phone "
                + "FROM reports r JOIN statuses s ON r.status_id = s.id "
                + "LEFT JOIN report_assignments a ON a.report_id = r.id "
                + "LEFT JOIN workers w ON w.id = a.worker_id "
                + "WHERE LOWER(TRIM(r.location)) = LOWER(TRIM(?)) AND s.name <> 'Resolved' "
                + "ORDER BY r.id";
        try (Connection c = DBConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, suburb);
            try (ResultSet rs = ps.executeQuery()) {
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    String worker = rs.getString(5);
                    System.out.println("PC-" + rs.getInt(1) + " | " + pretty(rs.getString(2))
                            + " | " + rs.getString(3) + " | " + rs.getString(4)
                            + " | Worker: " + (worker == null ? "not assigned"
                            : worker + " (" + nz(rs.getString(6)) + ")"));
                }
                if (!any) {
                    System.out.println("No open reports in this suburb.");
                }
            }
        } catch (SQLException e) {
            System.out.println("Could not load reports: " + e.getMessage());
        }
    }

    // ---------- Helpers ----------

    private static int parseId(String ref) {
        if (!ref.startsWith("PC-")) {
            return -1;
        }
        try {
            return Integer.parseInt(ref.substring(3));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String nz(String s) {
        return (s == null || s.isBlank()) ? "-" : s;
    }

    private static String pretty(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String t = s.replace("_", " ").toLowerCase();
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }
}