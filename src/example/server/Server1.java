package example.server;

import kotha.KothaServer;

public class Server1 {

    public static void main(String[] args) {
        new KothaServer<Server1APIImpl>(Server1APIImpl.class).start(54555);
    }
}
