package server;

import common.API;
import common.Kotha;

import java.util.concurrent.Future;

public class API_Implmentation implements API {

    @Override
    public Future<String> appendHello(String s) {
        String result = s + "-hello";
        return Kotha.createFuture(result);
    }

    @Override
    public Future<Integer> appendZero(int i) {
        int ret = i * 10;
        return Kotha.createFuture(ret);
    }
}
