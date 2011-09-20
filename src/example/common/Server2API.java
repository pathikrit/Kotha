package example.common;

import kotha.KothaCommon.APIResult;

/**
 * Server 2 api
 * @author tuanlinh
 *
 */
public interface Server2API {
	public APIResult<Integer,Exception> appendZero(Integer i);
	public APIResult<Void,Exception> printOnServerConsole(String s);
}
