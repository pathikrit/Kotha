package kotha;

import static kotha.KothaCommon.error;
import static kotha.KothaCommon.methodSignatureCache;
import static kotha.KothaCommon.setup;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import kotha.KothaCommon.APIResult;
import kotha.KothaCommon.APIResultImpl;
import kotha.KothaCommon.RMIMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.esotericsoftware.kryonet.Client;
import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Listener;
import com.google.common.base.Stopwatch;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.net.HostAndPort;

public class KothaClient<T> {

    private final static Logger log = LoggerFactory.getLogger(KothaClient.class);

    private final static int CONNECTION_TIMEOUT_MS = 5 * 1000;

    private final static AtomicLong CLIENTS = new AtomicLong();
    private final static AtomicLong CLIENT_REQUESTS = new AtomicLong();

    private final static Set<Client> clients = Sets.newHashSet();
    private final static Multimap<Long, Connection> clientConnections = HashMultimap.create();
    private final static Map<Long, APIResultImpl<Object>> apiCalls = Maps.newConcurrentMap();
    private final static Map<Long, Stopwatch> timers = Maps.newConcurrentMap();

    private final static Map<String, Connection> serviceLocationCache = Maps.newHashMap();

    private final Class<T> apiClass;
    private final long clientId;

    public KothaClient(Class<T> apiClass) {
        this.apiClass = apiClass;
        this.clientId = CLIENTS.incrementAndGet();
    }

    public T connectTo(final String server1, final String... addresses) {
        try {
            Set<String> connectTo = Sets.newHashSet(server1);
            if (addresses != null) {
                Collections.addAll(connectTo, addresses);
            }

            for (String server : connectTo) {
                final Client client = new Client();

                setup(client, new Listener() {
                	CountDownLatch bootstrapped = new CountDownLatch(addresses.length);
                	
                    @Override
                    public void connected(Connection connection) {
                        super.connected(connection);
                        clientConnections.put(clientId, connection);
                    }

                    @Override
                    public void disconnected(Connection connection) {
                        super.disconnected(connection);
                        clientConnections.get(clientId).remove(connection);
                    }

                    @Override
                    @SuppressWarnings("unchecked")
                    public void received(Connection connection, Object message) {
                        super.received(connection, message);
                        if (message instanceof List<?>) {
                            for (String method : (List<String>) message) {
                                if (serviceLocationCache.put(method, connection) != null) {
                                    log.warn("Multiple implementations of " + method + " found");
                                }
                            }
                            bootstrapped.countDown();
                        } else if (message instanceof RMIMessage) {
                        	try { bootstrapped.await(); // wait until we receive the api from server
							} catch (InterruptedException e1) { 
								disconnect();
								return;
							} 
                        	
                            RMIMessage rmiMessage = (RMIMessage) message;
                            APIResult<?, Exception> result = (APIResult<?, Exception>)rmiMessage.args[0];
                            try { apiCalls.remove(rmiMessage.id).done(result.get()); }
                            catch(Exception e) { apiCalls.remove(rmiMessage.id).done(e); }
                            log.info(rmiMessage.methodName + " call took " + timers.remove(rmiMessage.id).stop());
                        }
                    }
                });

                HostAndPort serverLocation = HostAndPort.fromString(server);
                try {
                    client.connect(CONNECTION_TIMEOUT_MS, serverLocation.getHostText(), serverLocation.getPort());
                    clients.add(client);
                } catch (Throwable t) {
                    error(log, "Could not connect to " + server, t);
                }
            }

            return getRemoteCaller();
        } catch (Throwable t) {
            return error(log, "Could not start client", t);
        }
    }
    
    public void disconnect() {
    	// Closing all the clients;
    	Iterator<Client> client = clients.iterator();
    	while(client.hasNext()) {
    		client.next().stop();
    		client.remove();
    	}
    	clientConnections.clear();
    	
    	// Finishing all the api calls with an exception
    	Iterator<APIResultImpl<Object>> result = apiCalls.values().iterator();
    	while(result.hasNext()) {
    		result.next().done(new RuntimeException("Client is disconected"));
    		result.remove();
    	}
    }

    @SuppressWarnings("unchecked")
    private T getRemoteCaller() {
        return (T) Proxy.newProxyInstance(apiClass.getClassLoader(), new Class[]{apiClass}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                final String methodSignature = methodSignatureCache.get(method);
                final Connection connection = serviceLocationCache.get(methodSignature);
                if (connection == null || !clientConnections.get(clientId).contains(connection)) {
                    return error(log, "Could not find server implementing " + methodSignature, null);
                }
                return makeServerCall(connection, method.getName(), args == null ? new Object[0] : args);
            }
        });
    }

    private static APIResult<Object,Exception> makeServerCall(Connection wire, String methodName, Object... args) {
        final long id = CLIENT_REQUESTS.incrementAndGet();
        timers.put(id, new Stopwatch().start());
        APIResultImpl<Object> result = new APIResultImpl<Object>();
        apiCalls.put(id, result);
        wire.sendTCP(new RMIMessage(id, methodName, args));
        return result;
    }
}
