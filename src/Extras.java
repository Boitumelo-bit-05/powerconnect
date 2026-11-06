package powerconnect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class Extras {

    public static void init() {
        try (Connection c = DBConnection.getConnection();
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS notifications ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, account_id INTEGER NOT NULL, "
                    + "report_id INTEGER, message TEXT NOT NULL, "
                    + "is_read INTEGER NOT NULL DEFAULT 0, created_at TEXT DEFAULT CURRENT_TIMESTAMP, "
                    + "FOREIGN KEY (account_id) REFERENCES accounts(id))");
            st.execute("CREATE TABLE IF NOT EXISTS report_events ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, report_id INTEGER NOT NULL, "
                    + "actor TEXT NOT NULL, event TEXT NOT NULL, created_at TEXT DEFAULT CURRENT_TIMESTAMP, "
                    + "FOREIGN KEY (report_id) REFERENCES reports(id))");
            String[] alters = {
                "ALTER TABLE reports ADD COLUMN photo TEXT",
                "ALTER TABLE reports ADD COLUMN resident_confirmed INTEGER",
                "ALTER TABLE reports ADD COLUMN problem_type TEXT"
            };
            for (String alter : alters) {
                try {
                    st.execute(alter);
                } catch (SQLException e) {
                    // the column already exists
                }
            }
        } catch (SQLException e) {
            System.out.println("DB error: " + e.getMessage());
        }
    }
}