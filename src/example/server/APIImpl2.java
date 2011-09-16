package example.server;

import com.google.common.util.concurrent.Futures;
import example.common.API;

import java.util.concurrent.Future;

public abstract class APIImpl2 implements API {

    @Override
    public Future<Integer> appendZero(Integer i) {
        int ret = i * 10;
        return Futures.immediateFuture(ret);
    }

    @Override
    public Future<Void> printOnServerConsole(String s) {
        System.out.println("Request to print: " + s);
        return Futures.immediateFuture(null);
    }
}
