/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Main.java to edit this template
 */
package powerconnect;

/**
 *
 * @author tochi
 */
public class PowerConnect {    
    
    public PowerConnect() {
      
    }

    public static void main(String[] args) {

        System.out.println("================================");
        System.out.println("   WELCOME TO POWER CONNECT");
        System.out.println("================================");

        
        CommunityMember member = new CommunityMember(
            1,
            "John Banda",
            "0977000000",
            "Lusaka"
        );

        
        System.out.println("\nCommunity Member Details");
        System.out.println("Name: " + member.getName());
        System.out.println("Contact: " + member.getContact());
        System.out.println("Address: " + member.getAddress());

        
        System.out.println("\nCommunity Member Actions");
        member.submitReport();
        member.confirmResolution();

        System.out.println("\nPower Connect application started successfully.");
    }
}



    