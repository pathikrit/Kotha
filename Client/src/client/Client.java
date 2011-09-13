package client;

import common.API;
import common.Kotha;

import java.util.concurrent.Future;

public class Client {

    public static void main(String[] args) {
        API api = Kotha.connectToServer("localhost", 54555, 54777);

        Future<String> res = api.appendHello("g");

        while (!res.isDone()) {
            System.out.println("not done yet!");
        }

        System.out.println(Kotha.getValueFromFuture(res));

    }
}
