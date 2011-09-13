package server;

import common.kotha.Kotha;

public class Server {

    public static void main(String[] args) {
        Kotha.startServer(54555, 54777, new API_Implmentation());
    }

}
