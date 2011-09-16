package example.server;

import com.google.common.util.concurrent.Futures;
import example.common.API;
import kotha.Kotha;

import java.util.concurrent.Future;

public class APIImpl2 implements API {

    @Override
    @Kotha.NotImplemented
    public Future<String> join(String a, String b) {
        return null;
    }

    @Override
    public Future<Integer> appendZero(Integer i) {
        int ret = i * 10;
        return Futures.immediateFuture(ret);
    }

    @Override
    @Kotha.NotImplemented
    public Future<Double> getPi() {
        return null;
    }

    @Override
    public Future<Void> printOnServerConsole(String s) {
        System.out.println("Request to print: " + s);
        return Futures.immediateFuture(null);
    }
}
