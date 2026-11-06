
package powerconnect;

public class FieldTechnician extends User {

    private String skillArea;

    
    public FieldTechnician(int userId, String name,
                           String contact, String skillArea) {
        super(userId, name, contact);
        this.skillArea = skillArea;
    }

    
    public String getSkillArea() {
        return skillArea;
    }

    
    public void setSkillArea(String skillArea) {
        if (skillArea != null && !skillArea.trim().isEmpty()) {
            this.skillArea = skillArea.trim();
        }
    }

    
    public void updateJobStatus() {
        System.out.println(
            "Technician " + getName()
            + " is checking assigned jobs."
        );
    }

    
    public void logCompletion() {
        System.out.println(
            "Technician " + getName()
            + " has logged job completion."
        );
    }

    
    public void displayTechnician() {
        displayUser();
        System.out.println("Skill Area: " + skillArea);
    }
}
