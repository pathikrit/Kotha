package example.client;

import com.google.common.util.concurrent.Futures;

import example.common.API;
import kotha.Kotha;

import java.util.concurrent.Future;

public class Client {

    public static void main(String[] args) {
        API api = Kotha.connectToServer(API.class, "localhost:54555");

        testApiCall(api.join("hello", "world"));
        testApiCall(api.appendZero(23));
        testApiCall(api.getPi());
        testApiCall(api.printOnServerConsole("howdy"));
    }

    private static void testApiCall(Future<?> f) {
        while (!f.isDone()) {
            //System.out.println('.');
        }
        System.out.println("Got result from server: " + Futures.getUnchecked(f));
    }
}
