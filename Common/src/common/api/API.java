package common.api;

import java.util.concurrent.Future;

public interface API {

    public Future<String> appendHello(String s);

    public Future<Integer> appendZero(int i);
}
