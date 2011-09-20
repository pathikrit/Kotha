package example.server;

import kotha.KothaServer;

public class Server2 {

    public static void main(String[] args) {
        new KothaServer<Server2APIImpl>(Server2APIImpl.class).start(54556);
    }
}
