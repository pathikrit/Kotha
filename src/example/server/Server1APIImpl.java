package example.server;

import kotha.KothaCommon.APIResult;
import kotha.KothaCommon.APIResultImpl;
import example.common.Server1API;

public class Server1APIImpl implements Server1API {

    @Override
    public APIResult<String,Exception> join(String a, String b) {
        String result = a + "-" + b;
        return new APIResultImpl<String>(result);
    }

    @Override
    public APIResult<Double,Exception> getPi() {
        return new APIResultImpl<Double>(3.14);
    }
}
