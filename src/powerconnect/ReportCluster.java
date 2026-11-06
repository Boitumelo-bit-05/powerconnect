package powerconnect;

import java.util.ArrayList;
import java.util.List;

public class ReportCluster {
    private String category;
    private String location;
    private List<Report> reports = new ArrayList<>();
    private boolean flagged;

    public ReportCluster(String category, String location) {
        this.category = category;
        this.location = location;
    }

    public void addReport(Report report) { reports.add(report); }
    public int getReportCount() { return reports.size(); }
    public void setFlagged(boolean flagged) { this.flagged = flagged; }
    public boolean isFlagged() { return flagged; }
    public String getCategory() { return category; }
    public String getLocation() { return location; }
    public List<Report> getReports() { return reports; }
}