/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package powerconnect;


public class CommunityMember extends User {

    private String address;

    
    public CommunityMember(int userId, String name, String contact, String address) {
        super(userId, name, contact);
        this.address = address;
    }

    
    public String getAddress() {
        return address;
    }

   
    public void setAddress(String address) {
        this.address = address;
    }

    
    public void submitReport() {
        System.out.println(getName() + " is submitting a community report.");
    }

    
    public void confirmResolution() {
        System.out.println(getName() + " has confirmed that the issue is resolved.");
    }
}

    
