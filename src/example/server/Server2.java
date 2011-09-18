package example.server;

import kotha.KothaServer;

public class Server2 {

    public static void main(String[] args) {
        KothaServer.startServer(54556, APIImpl2.class);
    }

}
