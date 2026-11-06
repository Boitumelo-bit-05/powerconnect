
package powerconnect;


public class ServiceCategory1 {
    private ServiceCategory category;

   
    public ServiceCategory1(ServiceCategory category) {
        if (category == null) {
            throw new IllegalArgumentException(
                "Service category cannot be null."
            );
        }

        this.category = category;
    }

    
    public ServiceCategory getCategory() {
        return category;
    }

    
    public String getName() {
        return category.getDisplayName();
    }

    
    public void setCategory(ServiceCategory category) {
        if (category == null) {
            throw new IllegalArgumentException(
                "Service category cannot be null."
            );
        }

        this.category = category;
    }

    @Override
    public String toString() {
        return getName();
    }
}
