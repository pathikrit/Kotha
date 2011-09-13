package client;

import com.esotericsoftware.kryonet.Client;
import common.api.API;
import common.kotha.Kotha;
import java.util.concurrent.Future;

public class ClientAPI implements API {

    private final Client client;

    public ClientAPI(Client client) {
        this.client = client;
    }

    @Override
    public Future<String> appendHello(String s) {
        return Kotha.testCall(client, s);
    }

    @Override
    public Future<Integer> appendZero(int i) {
        return null;
    }
}
