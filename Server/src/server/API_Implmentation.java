package server;

import com.google.common.util.concurrent.Futures;

import common.API;

import java.util.concurrent.Future;

public class API_Implmentation implements API {

    @Override
    public Future<String> join(String a, String b) {
        String result = a + "-" + b;
        return Futures.immediateFuture(result);
    }

    @Override
    public Future<Integer> appendZero(Integer i) {
        int ret = i * 10;
        return Futures.immediateFuture(ret);
    }

    @Override
    public Future<Double> getPi() {
        return Futures.immediateFuture(3.14);
    }

    @Override
    public Future<Void> printOnServerConsole(String s) {
        System.out.println("Request to print: " + s);
        return Futures.immediateFuture(null);
    }
}
