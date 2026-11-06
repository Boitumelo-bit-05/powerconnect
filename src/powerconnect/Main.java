package powerconnect;

import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        DatabaseSetUp.init();
        Utilities.init();
        DuplicateDetector.init();

        Scanner input = new Scanner(System.in);
        ReportManager manager = new ReportManager();

        manager.runAutoEscalation();

        int choice;
        do {
            System.out.println("\n=== POWER CONNECT ===");
            System.out.println("1. Submit a report");
            System.out.println("2. View all reports");
            System.out.println("3. Update report status");
            System.out.println("4. Run Cluster Analysis");
            System.out.println("5. Report messages");
            System.out.println("6. Rate a resolved report");
            System.out.println("7. Check auto-escalation");
            System.out.println("8. Analytics dashboard");
            System.out.println("9. Map view: open reports by suburb");
            System.out.println("10. Export dashboard to HTML");
            System.out.println("11. Councillors, hotlines & workers");
            System.out.println("12. Assign a worker to a report");
            System.out.println("13. Contacts for a report");
            System.out.println("14. Chat with the assistant");
            System.out.println("15. Exit");
            System.out.print("Choose an option: ");

            try {
                choice = Integer.parseInt(input.nextLine().trim());
            } catch (NumberFormatException e) {
                choice = -1;
            }

            switch (choice) {
                case 1 -> {
                    System.out.print("Your name: ");
                    String name = input.nextLine();

                    System.out.print("Category (Electricity, Water, Sanitation): ");
                    String category = input.nextLine();

                    System.out.print("Description: ");
                    String description = input.nextLine();

                    System.out.print("Location: ");
                    String location = input.nextLine();

                    DuplicateDetector.Match match =
                            DuplicateDetector.check(category, description, location);
                    if (match != null && DuplicateDetector.confirm(input, match)) {
                        DuplicateDetector.link(match, name, description);
                    } else {
                        manager.submitReport(category, description, location, name);
                    }
                }
                case 2 -> manager.viewReports();
                case 3 -> {
                    System.out.print("Report reference: ");
                    String id = input.nextLine();

                    System.out.print("New status (Assigned, In Progress, Resolved): ");
                    String status = input.nextLine();

                    manager.updateReportStatus(id, status);
                }
                case 4 -> manager.runClusterAnalysis();
                case 5 -> {
                    System.out.print("Report reference: ");
                    String id = input.nextLine();
                    manager.viewMessages(id);

                    System.out.print("Send a message? (y/n): ");
                    if (input.nextLine().trim().equalsIgnoreCase("y")) {
                        System.out.print("Your name: ");
                        String sender = input.nextLine();
                        System.out.print("Message: ");
                        String text = input.nextLine();
                        manager.sendMessage(id, sender, text);
                    }
                }
                case 6 -> {
                    System.out.print("Report reference: ");
                    String id = input.nextLine();

                    System.out.print("Rating (1-5): ");
                    int rating;
                    try {
                        rating = Integer.parseInt(input.nextLine().trim());
                    } catch (NumberFormatException e) {
                        rating = 0;
                    }

                    System.out.print("Comment (optional): ");
                    String comment = input.nextLine();

                    manager.submitFeedback(id, rating, comment);
                }
                case 7 -> manager.runAutoEscalation();
                case 8 -> Analytics.showDashboard();
                case 9 -> Analytics.showOpenReportsMap();
                case 10 -> Analytics.exportHtml();
                case 11 -> Directory.menu(input);
                case 12 -> {
                    String ref = Directory.assignWorker(input);
                    if (ref != null) {
                        manager.updateReportStatus(ref, "Assigned");
                    }
                }
                case 13 -> Directory.showReportContacts(input);
                case 14 -> Chatbot.start(input);
                case 15 -> System.out.println("Goodbye!");
                default -> System.out.println("Invalid menu option.");
            }
        } while (choice != 15);

        input.close();
    }
}