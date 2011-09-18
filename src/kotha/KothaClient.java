package kotha;

import com.esotericsoftware.kryonet.Client;
import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Listener;

import com.google.common.base.Stopwatch;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.net.HostAndPort;
import com.google.common.util.concurrent.SettableFuture;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static kotha.Kotha.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KothaClient<T> {

    private final static Logger log = LoggerFactory.getLogger(KothaClient.class);

    private final static int CONNECTION_TIMEOUT_MS = 5 * 1000;

    private final static AtomicLong CLIENTS = new AtomicLong();
    private final static AtomicLong CLIENT_REQUESTS = new AtomicLong();

    private final static Multimap<Long, Connection> clientConnections = HashMultimap.create();
    private final static Map<Long, SettableFuture<Object>> apiCalls = Maps.newConcurrentMap();
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
                        } else if (message instanceof RMIMessage) {
                            RMIMessage rmiMessage = (RMIMessage) message;
                            apiCalls.remove(rmiMessage.id).set(rmiMessage.args[0]);
                            log.info(rmiMessage.methodName + " call took " + timers.remove(rmiMessage.id).stop());
                        }
                    }
                });

                HostAndPort serverLocation = HostAndPort.fromString(server);
                try {
                    client.connect(CONNECTION_TIMEOUT_MS, serverLocation.getHostText(), serverLocation.getPort());
                } catch (Throwable t) {
                    error(log, "Could not connect to " + server, t);
                }
            }

            return getRemoteCaller();
        } catch (Throwable t) {
            return error(log, "Could not start client", t);
        }
    }

    @SuppressWarnings("unchecked")
    private T getRemoteCaller() {
        return (T) Proxy.newProxyInstance(apiClass.getClassLoader(), new Class[]{apiClass}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                final String methodSignature = getMethodSignature(method);
                final Connection connection = serviceLocationCache.get(methodSignature);
                if (connection == null || !clientConnections.get(clientId).contains(connection)) {
                    return error(log, "Could not find server implementing " + methodSignature, null);
                }
                return makeServerCall(connection, method.getName(), args == null ? new Object[0] : args);
            }
        });
    }

    private static Future<Object> makeServerCall(Connection wire, String methodName, Object... args) {
        final long id = CLIENT_REQUESTS.incrementAndGet();
        timers.put(id, new Stopwatch().start());
        SettableFuture<Object> future = SettableFuture.create();
        apiCalls.put(id, future);
        wire.sendTCP(new RMIMessage(id, methodName, args));
        return future;
    }
}
