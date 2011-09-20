package example.client;

import kotha.KothaClient;
import kotha.KothaCommon.APIResult;
import example.common.API;

public class Client {

    public static void main(String[] args) throws InterruptedException, Exception {
    	KothaClient<API> kothaClient = new KothaClient<API>(API.class);
        API api = kothaClient.connectTo("localhost:54555", "localhost:54556");
        try {
	        testApiCall(api.join("hello", "world"));
	        testApiCall(api.appendZero(23));
	        testApiCall(api.getPi());
	        testApiCall(api.printOnServerConsole("howdy"));
        } finally {
        	kothaClient.disconnect();
        }
    }

    private static void testApiCall(APIResult<?,Exception> result) throws InterruptedException {
        try { System.out.println("Got result from server: " + result.blockingGet());
		} catch (Exception e) { e.printStackTrace(); }
    }
}
