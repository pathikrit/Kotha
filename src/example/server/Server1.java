package example.server;

import kotha.Kotha;

public class Server1 {

    public static void main(String[] args) {
        Kotha.startServer(54555, APIImpl1.class);
    }

}
