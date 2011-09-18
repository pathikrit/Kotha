package example.server;

import kotha.KothaServer;

public class Server1 {

    public static void main(String[] args) {
        new KothaServer<APIImpl1>(APIImpl1.class).start(54555);
    }

}
