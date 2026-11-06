package powerconnect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseSetUp {
    public static void init() {
        String[] sql = {
            "CREATE TABLE IF NOT EXISTS users ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS statuses ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE)",
            "CREATE TABLE IF NOT EXISTS reports ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT NOT NULL, "
                + "description TEXT, location TEXT NOT NULL, status_id INTEGER NOT NULL, "
                + "created_at TEXT DEFAULT CURRENT_TIMESTAMP, "
                + "FOREIGN KEY (status_id) REFERENCES statuses(id))",
            "CREATE TABLE IF NOT EXISTS report_status_history ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, report_id INTEGER NOT NULL, "
                + "status_id INTEGER NOT NULL, changed_by INTEGER NOT NULL, notes TEXT, "
                + "changed_at TEXT DEFAULT CURRENT_TIMESTAMP, "
                + "FOREIGN KEY (report_id) REFERENCES reports(id), "
                + "FOREIGN KEY (status_id) REFERENCES statuses(id), "
                + "FOREIGN KEY (changed_by) REFERENCES users(id))",
            "CREATE TABLE IF NOT EXISTS messages ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, report_id INTEGER NOT NULL, "
                + "sender TEXT NOT NULL, body TEXT NOT NULL, "
                + "sent_at TEXT DEFAULT CURRENT_TIMESTAMP, "
                + "FOREIGN KEY (report_id) REFERENCES reports(id))",
            "CREATE TABLE IF NOT EXISTS feedback ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, report_id INTEGER NOT NULL UNIQUE, "
                + "rating INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 5), comment TEXT, "
                + "submitted_at TEXT DEFAULT CURRENT_TIMESTAMP, "
                + "FOREIGN KEY (report_id) REFERENCES reports(id))",
            "CREATE TABLE IF NOT EXISTS officials ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, "
                + "title TEXT NOT NULL DEFAULT 'Councillor', suburb TEXT NOT NULL, "
                + "phone TEXT, hotline TEXT)",
            "CREATE TABLE IF NOT EXISTS workers ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, "
                + "specialty TEXT, phone TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS report_assignments ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, report_id INTEGER NOT NULL UNIQUE, "
                + "worker_id INTEGER NOT NULL, assigned_at TEXT DEFAULT CURRENT_TIMESTAMP, "
                + "FOREIGN KEY (report_id) REFERENCES reports(id), "
                + "FOREIGN KEY (worker_id) REFERENCES workers(id))",
            "INSERT OR IGNORE INTO statuses (name) VALUES "
                + "('Reported'), ('Assigned'), ('In Progress'), ('Resolved')",
            "INSERT INTO users (name) SELECT 'System' "
                + "WHERE NOT EXISTS (SELECT 1 FROM users)"
        };
        try (Connection c = DBConnection.getConnection();
             Statement st = c.createStatement()) {
            for (String s : sql) {
                st.execute(s);
            }
            System.out.println("Database ready.");
        } catch (SQLException e) {
            System.out.println("DB error: " + e.getMessage());
        }
    }
}