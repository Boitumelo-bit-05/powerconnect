package powerconnect;

public class WebMain {
    public static void main(String[] args) throws Exception {
        DatabaseSetUp.init();
        Utilities.init();
        DuplicateDetector.init();
        Accounts.init();
        Extras.init();

        String adminPassword = Accounts.ensureAdmin();

        WebServer.start(8080);
        System.out.println("PowerConnect is running at http://localhost:8080");
        if (adminPassword != null) {
            System.out.println("==================================================");
            System.out.println("FIRST RUN: admin login created");
            System.out.println("  username: admin");
            System.out.println("  password: " + adminPassword);
            System.out.println("Save it now. It is shown only once.");
            System.out.println("==================================================");
        }
        System.out.println("Stop the server with the red square in the Output panel.");
    }
}