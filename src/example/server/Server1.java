package example.server;

import kotha.KothaServer;

public class Server1 {

    public static void main(String[] args) {
        KothaServer.startServer(54555, APIImpl1.class);
    }

}
