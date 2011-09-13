package server;

import common.API;
import common.Kotha;

import java.util.concurrent.Future;

public class API_Implmentation implements API {

    @Override
    public Future<String> join(String a, String b) {
        String result = a + "-" + b;
        return Kotha.createFuture(result);
    }

    @Override
    public Future<Integer> appendZero(Integer i) {
        int ret = i * 10;
        return Kotha.createFuture(ret);
    }

    @Override
    public Future<Double> getPi() {
        return Kotha.createFuture(3.14);
    }

    @Override
    public Future<Void> printOnServerConsole(String s) {
        System.out.println("Request to print: " + s);
        return Kotha.createFuture(null);
    }
}
