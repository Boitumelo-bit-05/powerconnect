package powerconnect;

public class Report {
    private String reportId;
    private String description;
    private String location;
    private String status;
    private ServiceCategory1 category;

    public Report(String reportId, ServiceCategory1 category, String description, String location) {
        this.reportId = reportId;
        this.description = description;
        this.location = location;
        this.category = category;
        this.status = "Reported";
    }

    public String getReportId() {
        return reportId;
    }

    public String getDescription() {
        return description;
    }

    public String getLocation() {
        return location;
    }

    public String getStatus() {
        return status;
    }

    public ServiceCategory1 getCategory() {
        return category;
    }

    public void updateStatus(String newStatus) {
        if (newStatus == null || newStatus.trim().isEmpty()) {
            System.out.println("Error: Status cannot be empty.");
            return;
        }
        this.status = newStatus.trim();
        System.out.println("Report status updated to: " + this.status);
    }

    public void setStatusQuietly(String status) {
        this.status = status;
    }

    public void displayReport() {
        System.out.println("Report ID: " + reportId);
        System.out.println("Description: " + description);
        System.out.println("Location: " + location);
        System.out.println("Category: " + category);
        System.out.println("Status: " + status);
    }
}