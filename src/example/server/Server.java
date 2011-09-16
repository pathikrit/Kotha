package example.server;

import kotha.Kotha;

public class Server {

    public static void main(String[] args) {
        Kotha.startServer(54555, new API_Implmentation());
    }

}
