package example.client;

import com.google.common.util.concurrent.Futures;
import example.common.API;
import java.util.concurrent.Future;
import kotha.KothaClient;

public class Client {

    public static void main(String[] args) {
        API api = new KothaClient<API>(API.class).connectTo("localhost:54555", "localhost:54556");

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
