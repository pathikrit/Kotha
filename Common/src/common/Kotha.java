package common;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryonet.Client;
import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.EndPoint;
import com.esotericsoftware.kryonet.Listener;
import com.esotericsoftware.kryonet.Server;

import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.net.HostAndPort;
import com.google.common.primitives.Primitives;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Kotha {

    private final static Logger log = LoggerFactory.getLogger(Kotha.class);

    private final static int CONNECTION_TIMEOUT_MS = 5 * 1000;
    private final static int MAX_SERVER_THREADS = 20;

    private final static AtomicLong CLIENT_REQUESTS = new AtomicLong();

    private final static Map<Client, Connection> connections = Maps.newHashMap();
    private final static Map<Long, SettableFuture<Object>> apiCalls = Maps.newConcurrentMap();
    private final static Map<Long, Stopwatch> timers = Maps.newConcurrentMap();

    private Kotha() {
    }

    public static void startServer(int tcpPort, final API apiImpl) {
        try {
            final Executor executor = Executors.newFixedThreadPool(MAX_SERVER_THREADS);
            Server server = new Server();
            setup(server, new Listener() {
                @Override
                public void received(final Connection connection, final Object message) {
                    if (message instanceof RMIMessage) {
                        executor.execute(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    connection.sendTCP(((RMIMessage) message).invokeOn(apiImpl));
                                } catch (Throwable t) {
                                    error("Error in server api call", t);
                                }
                            }
                        });
                    }
                }
            });
            server.bind(tcpPort);
        } catch (Throwable t) {
            error("Could not start server", t);
        }
    }

    public static API connectToServer(String address) {
        try {
            final Client client = new Client();
            setup(client, new Listener() {
                @Override
                public void connected(Connection connection) {
                    connections.put(client, connection);
                }

                @Override
                public void disconnected(Connection connection) {
                    connections.remove(client);
                }

                @Override
                public void received(Connection connection, Object message) {
                    if (message instanceof RMIMessage) {
                        RMIMessage rmiMessage = (RMIMessage) message;
                        apiCalls.remove(rmiMessage.id).set(rmiMessage.args.get(0));
                        log.info(rmiMessage.methodName + " call took " + timers.remove(rmiMessage.id).stop());
                    }
                }
            });

            HostAndPort serverLocation = HostAndPort.fromString(address);
            client.connect(CONNECTION_TIMEOUT_MS, serverLocation.getHostText(), serverLocation.getPort());
            return getInstance(client);
        } catch (Throwable t) {
            return error("Could not start client", t);
        }
    }

    static API getInstance(final Client client) {
        return (API) Proxy.newProxyInstance(API.class.getClassLoader(), new Class[]{API.class}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                return makeServerCall(connections.get(client), method.getName(), args == null ? new Object[0] : args);
            }
        });
    }

    static Future<Object> makeServerCall(Connection wire, String methodName, Object... args) {
        final long id = CLIENT_REQUESTS.incrementAndGet();
        timers.put(id, new Stopwatch().start());
        SettableFuture<Object> future = SettableFuture.create();
        apiCalls.put(id, future);
        wire.sendTCP(new RMIMessage(id, methodName, Lists.newArrayList(args)));
        return future;
    }

    private static void setup(EndPoint endPoint, Listener listener) {
        Kryo kryo = endPoint.getKryo();
        kryo.register(RMIMessage.class);
        kryo.register(ArrayList.class); // TODO: More Kryo serializers: https://github.com/magro/kryo-serializers
        endPoint.addListener(listener);
        endPoint.start();
    }

    static <T> T error(String message, Throwable cause) {
        log.error(message, cause);
        return null;
    }
}

class RMIMessage {

    final long id;
    final String methodName;
    final List<?> args; // TODO: just use Object array?

    final static transient Map<String, Method> methodCache = Maps.newHashMap();

    RMIMessage() {
        this(0, null, null);
    }

    RMIMessage(long id, String methodName, List<?> args) {
        this.id = id;
        this.methodName = methodName;
        this.args = args;
    }

    RMIMessage invokeOn(API api) {
        Object[] argsArr = args.toArray();

        Class<?>[] paramTypes = new Class[argsArr.length];
        for (int i = 0; i < argsArr.length; i++) {
            paramTypes[i] = argsArr[i] == null ? null : argsArr[i].getClass();
        }

        final Class<? extends API> apiClass = api.getClass();
        final String key = apiClass.getName() + methodName + Arrays.toString(paramTypes);

        Method method = methodCache.get(key);

        if (method == null) {
            try {
                method = apiClass.getDeclaredMethod(methodName, paramTypes);
            } catch (NoSuchMethodException e) {
                for (Class<?> current = apiClass; current != null; current = current.getSuperclass()) {
                    for (Method m : current.getDeclaredMethods()) {
                        if (m.getName().equals(methodName) && areParamsExtendable(paramTypes, m.getParameterTypes())) {
                            method = m;
                            break;
                        }
                    }
                }
            }
            methodCache.put(key, method);
        }

        try {
            return new RMIMessage(id, methodName, Lists.newArrayList(Futures.getUnchecked((Future<?>) method.invoke(api, argsArr))));
        } catch (Throwable t) {
            return Kotha.error("Could not invoke " + methodName, t);
        }
    }

    static boolean areParamsExtendable(Class<?>[] classes, Class<?>[] superClasses) {
        for (int i = 0; i < classes.length; i++) {
            if (classes[i] != null && !Primitives.wrap(superClasses[i]).isAssignableFrom(Primitives.wrap(classes[i]))) {
                return false;
            }
        }
        return classes.length == superClasses.length;
    }
}
