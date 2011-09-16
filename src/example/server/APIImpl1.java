package example.server;

import com.google.common.util.concurrent.Futures;
import example.common.API;

import java.util.concurrent.Future;

public abstract class APIImpl1 implements API {

    @Override
    public Future<String> join(String a, String b) {
        String result = a + "-" + b;
        return Futures.immediateFuture(result);
    }

    @Override
    public Future<Double> getPi() {
        return Futures.immediateFuture(3.14);
    }
}
