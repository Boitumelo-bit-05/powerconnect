
package powerconnect;

/**
 *
 * @author tochi
 */
public class ReportStatus {
    
    public static final String REPORTED = "Reported";
    public static final String ASSIGNED = "Assigned";
    public static final String IN_PROGRESS = "In Progress";
    public static final String RESOLVED = "Resolved";

    public ReportStatus() {    
    }
    
    public static boolean isValidStatus(String status) {
        if (status == null) {
            return false;
        }
        
        return status.equals(REPORTED) 
                || status.equals(ASSIGNED) 
                || status.equals(IN_PROGRESS)
                || status.equals(RESOLVED);
        
    }
    
    public static void displayStatuses() {
        System.out.println("Available Report Statuses:");
        System.out.println("1. " + REPORTED);
        System.out.println("2. " + ASSIGNED);
        System.out.println("3. " + IN_PROGRESS);
        System.out.println("4. " + RESOLVED);
    }
}
    
