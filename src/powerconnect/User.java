
package powerconnect;


public abstract class User {

    private int userId;
    private String name;
    private String contact;
    private boolean loggedIn;

    
    public User(int userId, String name, String contact) {
        this.userId = userId;
        this.name = name;
        this.contact = contact;
        this.loggedIn = false;
    }

    public int getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public String getContact() {
        return contact;
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    public void login() {
        if (!loggedIn) {
            loggedIn = true;
            System.out.println(name + " has logged in successfully.");
        } else {
            System.out.println(name + " is already logged in.");
        }
    }

    public void logout() {
        if (loggedIn) {
            loggedIn = false;
            System.out.println(name + " has logged out successfully.");
        } else {
            System.out.println(name + " is not logged in.");
        }
    }

    public void displayUser() {
        System.out.println("User ID: " + userId);
        System.out.println("Name: " + name);
        System.out.println("Contact: " + contact);
        System.out.println("Logged in: " + loggedIn);
    }
}

