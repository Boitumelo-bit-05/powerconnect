package powerconnect;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class Accounts {

    public static class Account {
        public final int id;
        public final String username;
        public final String role;
        public final String name;
        public final Integer officialId;
        public final Integer workerId;

        public Account(int id, String username, String role, String name,
                       Integer officialId, Integer workerId) {
            this.id = id;
            this.username = username;
            this.role = role;
            this.name = name;
            this.officialId = officialId;
            this.workerId = workerId;
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,30}");
    private static final int ITERATIONS = 120_000;
    private static final String DUMMY = hash("not-a-real-password");

    public static void init() {
        try (Connection c = DBConnection.getConnection();
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS accounts ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "username TEXT NOT NULL UNIQUE COLLATE NOCASE, "
                    + "password_hash TEXT NOT NULL, "
                    + "role TEXT NOT NULL CHECK (role IN ('resident','councillor','worker','admin')), "
                    + "display_name TEXT NOT NULL, official_id INTEGER, worker_id INTEGER, "
                    + "created_at TEXT DEFAULT CURRENT_TIMESTAMP)");
            String[] alters = {
                "ALTER TABLE reports ADD COLUMN account_id INTEGER",
                "ALTER TABLE report_confirmations ADD COLUMN account_id INTEGER"
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

    // Creates the first admin login if none exists. Returns the generated password, or null.
    public static String ensureAdmin() {
        try {
            if (count("SELECT COUNT(*) FROM accounts WHERE role = 'admin'") > 0) {
                return null;
            }
            String password = randomPassword();
            create("admin", password, "admin", "Administrator", null, null);
            return password;
        } catch (SQLException | IllegalArgumentException e) {
            System.out.println("Could not create the admin login: " + e.getMessage());
            return null;
        }
    }

    public static void validate(String username, String password, String role) throws SQLException {
        String u = username == null ? "" : username.trim();
        if (!USERNAME.matcher(u).matches()) {
            throw new IllegalArgumentException(
                    "Username must be 3 to 30 letters, numbers, dots, dashes or underscores.");
        }
        if (!"admin".equals(role) && u.equalsIgnoreCase("admin")) {
            throw new IllegalArgumentException("That username is not available.");
        }
        checkLength(password);
        try (Connection c = DBConnection.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM accounts WHERE username = ?")) {
            ps.setString(1, u);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    throw new IllegalArgumentException("That username is already taken.");
                }
            }
        }
    }

    public static Account create(String username, String password, String role, String name,
                                 Integer officialId, Integer workerId) throws SQLException {
        validate(username, password, role);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Please enter a name.");
        }
        String u = username.trim();
        String n = name.trim();
        try (Connection c = DBConnection.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO accounts (username, password_hash, role, display_name, "
                    + "official_id, worker_id) VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, u);
                ps.setString(2, hash(password));
                ps.setString(3, role);
                ps.setString(4, n);
                if (officialId == null) {
                    ps.setNull(5, java.sql.Types.INTEGER);
                } else {
                    ps.setInt(5, officialId);
                }
                if (workerId == null) {
                    ps.setNull(6, java.sql.Types.INTEGER);
                } else {
                    ps.setInt(6, workerId);
                }
                ps.executeUpdate();
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                rs.next();
                return new Account(rs.getInt(1), u, role, n, officialId, workerId);
            }
        }
    }

    // Returns the account if the username and password are right, otherwise null.
    public static Account authenticate(String username, String password) throws SQLException {
        String pw = password == null ? "" : password;
        String sql = "SELECT id, username, password_hash, role, display_name, official_id, worker_id "
                + "FROM accounts WHERE username = ?";
        try (Connection c = DBConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username == null ? "" : username.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    verify(pw, DUMMY);
                    return null;
                }
                if (!verify(pw, rs.getString(3))) {
                    return null;
                }
                return new Account(rs.getInt(1), rs.getString(2), rs.getString(4), rs.getString(5),
                        nullableInt(rs, 6), nullableInt(rs, 7));
            }
        }
    }

    public static void setPassword(int accountId, String newPassword) throws SQLException {
        checkLength(newPassword);
        try (Connection c = DBConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE accounts SET password_hash = ? WHERE id = ?")) {
            ps.setString(1, hash(newPassword));
            ps.setInt(2, accountId);
            ps.executeUpdate();
        }
    }

    // ---------- Internals ----------

    private static void checkLength(String password) {
        if (password == null || password.length() < 8 || password.length() > 100) {
            throw new IllegalArgumentException("Password must be 8 to 100 characters.");
        }
    }

    private static int count(String sql) throws SQLException {
        try (Connection c = DBConnection.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static Integer nullableInt(ResultSet rs, int col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static String randomPassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        HexFormat hex = HexFormat.of();
        return "pbkdf2$" + ITERATIONS + "$" + hex.formatHex(salt) + "$"
                + hex.formatHex(derive(password, salt, ITERATIONS));
    }

    private static boolean verify(String password, String stored) {
        try {
            String[] parts = stored.split("\\$");
            if (parts.length != 4) {
                return false;
            }
            HexFormat hex = HexFormat.of();
            byte[] expected = hex.parseHex(parts[3]);
            byte[] actual = derive(password, hex.parseHex(parts[2]), Integer.parseInt(parts[1]));
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Password hashing failed", e);
        }
    }
}