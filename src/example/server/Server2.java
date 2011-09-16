package example.server;

import kotha.Kotha;

public class Server2 {

    public static void main(String[] args) {
        Kotha.startServer(54556, APIImpl2.class);
    }

}
