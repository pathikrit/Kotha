package common;

import java.util.concurrent.Future;

public interface API {

    public Future<String> join(String a, String b);

    public Future<Integer> appendZero(Integer i);

    public Future<Double> getPi();

    public Future<Void> printOnServerConsole(String s);
}
