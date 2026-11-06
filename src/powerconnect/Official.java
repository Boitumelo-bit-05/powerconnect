
package powerconnect;

public class Official extends User {

    private String department;

    
    public Official(int userId, String name,
                    String contact, String department) {
        super(userId, name, contact);
        this.department = department;
    }

    
    public String getDepartment() {
        return department;
    }

    
    public void setDepartment(String department) {
        if (department != null && !department.trim().isEmpty()) {
            this.department = department.trim();
        }
    }

    
    public void reviewReport() {
        System.out.println(
            "Official " + getName()
            + " is ready to review service reports."
        );
        System.out.println("Department: " + department);
    }

    
    public Assignment assignTechnician(
            int assignmentId,
            Report report,
            FieldTechnician technician) {

        if (report == null || technician == null) {
            System.out.println(
                "Error: Report and technician are required."
            );
            return null;
        }

        Assignment assignment = new Assignment(
            assignmentId, report, technician
        );

        report.updateStatus("Assigned");

        System.out.println(
            "Official " + getName()
            + " assigned " + technician.getName()
            + " to report " + report.getReportId() + "."
        );

        return assignment;
    }

    
    public void displayOfficial() {
        displayUser();
        System.out.println("Department: " + department);
    }
}
    

