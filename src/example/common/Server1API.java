package example.common;

import kotha.KothaCommon.APIResult;

/**
 * Server 1 api
 * @author tuanlinh
 *
 */
public interface Server1API {
	public APIResult<String,Exception> join(String a, String b);
	public APIResult<Double,Exception> getPi();
}
