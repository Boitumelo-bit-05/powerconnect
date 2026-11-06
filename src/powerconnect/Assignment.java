/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package powerconnect;

import java.time.LocalDateTime;

public class Assignment {

    private int assignmentId;
    private Report report;
    private FieldTechnician technician;
    private LocalDateTime dateAssigned;
    private boolean completed;

    
    public Assignment(int assignmentId, Report report, FieldTechnician technician) {

        this.assignmentId = assignmentId;
        this.report = report;
        this.technician = technician;
        this.dateAssigned = LocalDateTime.now();
        this.completed = false;
    }

    
    public int getAssignmentId() {
        return assignmentId;
    }

    
    public Report getReport() {
        return report;
    }

    
    public FieldTechnician getTechnician() {
        return technician;
    }

    
    public LocalDateTime getDateAssigned() {
        return dateAssigned;
    }

   
    public boolean isCompleted() {
        return completed;
    }

    
    public void markComplete() {
        if (!completed) {
            completed = true;

            if (report != null) {
                report.updateStatus("Resolved");
            }

            System.out.println(
                "Assignment " + assignmentId
                + " has been completed."
            );
        } else {
            System.out.println(
                "This assignment is already completed."
            );
        }
    }

    
    public void displayAssignment() {
        System.out.println("Assignment ID: " + assignmentId);

        if (report != null) {
            System.out.println(
                "Report ID: " + report.getReportId()
            );
            System.out.println(
                "Description: " + report.getDescription()
            );
            System.out.println(
                "Report Status: " + report.getStatus()
            );
        }

        if (technician != null) {
            System.out.println(
                "Technician: " + technician.getName()
            );
        }

        System.out.println("Date Assigned: " + dateAssigned);
        System.out.println("Completed: " + completed);
    }
}
