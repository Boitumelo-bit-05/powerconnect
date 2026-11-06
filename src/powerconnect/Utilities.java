package powerconnect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class Utilities {

    public static void init() {
        try (Connection c = DBConnection.getConnection();
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS utilities ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, "
                    + "services TEXT NOT NULL, hotline TEXT, sms TEXT, whatsapp TEXT, "
                    + "email TEXT, notes TEXT)");
        } catch (SQLException e) {
            System.out.println("DB error: " + e.getMessage());
            return;
        }

        seed("ZESCO", "ELECTRICITY",
                "0211 363636 or 322", "SMS 3600 or USSD *3600#", "0762 626977",
                "contactcentre@zesco.co.zm", "Fault reporting, 24/7 call centre.");
        seed("Lusaka Water Supply & Sanitation (LWSC)", "WATER,SANITATION",
                "0211 251571 / 0211 250002, landline 0211 257579", "0975 618618 / 0950 948028", "",
                "customerservice@lwsc.com.zm",
                "Phone lines open Monday to Saturday. Check lwsc.com.zm/contact-us for current lines.");
        seed("NWASCO (water and sanitation regulator)", "WATER,SANITATION",
                "Complaints toll free 5252, phone 0211 226941/2", "", "",
                "", "Use if the utility does not respond to your complaint.");
    }

    private static void seed(String name, String services, String hotline, String sms,
                             String whatsapp, String email, String notes) {
        String sql = "INSERT OR IGNORE INTO utilities "
                + "(name, services, hotline, sms, whatsapp, email, notes) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = DBConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, services);
            ps.setString(3, hotline);
            ps.setString(4, sms);
            ps.setString(5, whatsapp);
            ps.setString(6, email);
            ps.setString(7, notes);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("Could not save utility contact: " + e.getMessage());
        }
    }

    public static void list() {
        String sql = "SELECT name, services, hotline, sms, whatsapp, email, notes "
                + "FROM utilities ORDER BY id";
        try (Connection c = DBConnection.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            System.out.println("\n--- Utility hotlines ---");
            boolean any = false;
            while (rs.next()) {
                any = true;
                print(rs);
            }
            if (!any) {
                System.out.println("No utility contacts saved.");
            }
        } catch (SQLException e) {
            System.out.println("Could not list utilities: " + e.getMessage());
        }
    }

    // Shows who to contact for a report category (e.g. ELECTRICITY, WATER).
    public static void printFor(String category) {
        if (category == null) {
            return;
        }
        String sql = "SELECT name, services, hotline, sms, whatsapp, email, notes FROM utilities "
                + "WHERE ',' || services || ',' LIKE '%,' || ? || ',%' ORDER BY id";
        try (Connection c = DBConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, category);
            try (ResultSet rs = ps.executeQuery()) {
                boolean header = false;
                while (rs.next()) {
                    if (!header) {
                        System.out.println("Who to contact about this fault:");
                        header = true;
                    }
                    print(rs);
                }
            }
        } catch (SQLException e) {
            System.out.println("Could not look up utility contacts: " + e.getMessage());
        }
    }

    // Used by the chatbot.
    public static String info(String lowerMessage) {
        String filter = null;
        if (lowerMessage.contains("zesco") || lowerMessage.contains("electric")) {
            filter = "ELECTRICITY";
        } else if (lowerMessage.contains("water") || lowerMessage.contains("lwsc")
                || lowerMessage.contains("nwasco") || lowerMessage.contains("sewer")
                || lowerMessage.contains("sanitation")) {
            filter = "WATER";
        }

        String sql = "SELECT name, hotline, sms, whatsapp, email FROM utilities"
                + (filter == null ? "" : " WHERE ',' || services || ',' LIKE '%,' || ? || ',%'")
                + " ORDER BY id";
        StringBuilder sb = new StringBuilder();
        try (Connection c = DBConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (filter != null) {
                ps.setString(1, filter);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sb.append("\n  ").append(rs.getString("name"));
                    append(sb, "hotline", rs.getString("hotline"));
                    append(sb, "sms/ussd", rs.getString("sms"));
                    append(sb, "whatsapp", rs.getString("whatsapp"));
                    append(sb, "email", rs.getString("email"));
                }
            }
        } catch (SQLException e) {
            return "Sorry, I couldn't look that up: " + e.getMessage();
        }
        return sb.length() == 0 ? "I don't have that utility's contact details yet."
                : "Utility contacts:" + sb;
    }

    private static void append(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append("\n      ").append(label).append(": ").append(value);
        }
    }

    private static void print(ResultSet rs) throws SQLException {
        System.out.println(rs.getString("name"));
        line("Hotline", rs.getString("hotline"));
        line("SMS/USSD", rs.getString("sms"));
        line("WhatsApp", rs.getString("whatsapp"));
        line("Email", rs.getString("email"));
        line("Note", rs.getString("notes"));
    }

    private static void line(String label, String value) {
        if (value != null && !value.isBlank()) {
            System.out.println("    " + label + ": " + value);
        }
    }
}
