package example.server;

import kotha.KothaServer;

public class Server2 {

    public static void main(String[] args) {
        new KothaServer<APIImpl2>(APIImpl2.class).start(54556);
    }
}
