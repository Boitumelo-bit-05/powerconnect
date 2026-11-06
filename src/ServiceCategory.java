
public enum ServiceCategory {
    
    WATER,
    ELECTRICITY,
    WASTE_MANAGEMENT,
    SANITATION,
    ROAD_MAINTENANCE;

    public String getDisplayName() {
        switch (this) {
            case WATER:
                return "Water";
            case ELECTRICITY:
                return "Electricity";
            case WASTE_MANAGEMENT:
                return "Waste Management";
            case SANITATION:
                return "Sanitation";
            case ROAD_MAINTENANCE:
                return "Road Maintenance";
            default:
                return this.name();
        }
    }
}