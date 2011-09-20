package example.server;

import kotha.KothaCommon.APIResult;
import kotha.KothaCommon.APIResultImpl;
import example.common.Server2API;

public class Server2APIImpl implements Server2API {

    @Override
    public APIResult<Integer, Exception> appendZero(Integer i) {
        int ret = i * 10;
        return new APIResultImpl<Integer>(ret);
    }

    @Override
    public APIResult<Void, Exception> printOnServerConsole(String s) {
        System.out.println("Request to print: " + s);
        return new APIResultImpl<Void>(null);
    }
}
