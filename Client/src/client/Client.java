package client;

import common.API;
import common.Kotha;

import java.util.concurrent.Future;

public class Client {

    public static void main(String[] args) {
        API api = Kotha.connectToServer("localhost", 54555, 54777);

        testApiCall(api.join("hello", "world"));
        testApiCall(api.appendZero(23));
        testApiCall(api.getPi());
        testApiCall(api.printOnServerConsole("howdy"));
    }

    private static void testApiCall(Future<?> f) {
        while (!f.isDone()) {
            System.out.print('.');
        }
        System.out.println(Kotha.getValueFromFuture(f));
    }
}