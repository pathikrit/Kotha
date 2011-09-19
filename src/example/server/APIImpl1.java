package example.server;

import com.google.common.util.concurrent.Futures;

import example.common.API;

import java.util.concurrent.Future;

import kotha.KothaServer;

public class APIImpl1 implements API {

    @Override
    public Future<String> join(String a, String b) {
        String result = a + "-" + b;
        return Futures.immediateFuture(result);
    }

    @Override
    @KothaServer.NotImplemented
    public Future<Integer> appendZero(Integer i) {
        return null;
    }

    @Override
    public Future<Double> getPi() {
        return Futures.immediateFuture(3.14);
    }

    @Override
    @KothaServer.NotImplemented
    public Future<Void> printOnServerConsole(String s) {
        return null;
    }
}
