package common.kotha;

import client.ClientAPI;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryonet.Client;
import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.EndPoint;
import com.esotericsoftware.kryonet.Listener;
import com.esotericsoftware.kryonet.Server;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.primitives.Primitives;
import com.google.common.util.concurrent.SettableFuture;
import common.api.API;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public class Kotha {

    private final static int CONNECTION_TIMEOUT_MS = 5 * 1000;
    private final static int MAX_SERVER_THREADS = 20;

    private final static AtomicLong CLIENT_REQUESTS = new AtomicLong();

    private final static Map<Client, Connection> connections = Maps.newHashMap();
    private final static Map<Long, SettableFuture<Object>> apiCalls = Maps.newConcurrentMap();

    private Kotha() {
    }

    public static void startServer(int tcpPort, int udpPort, final API apiImpl) {
        final Executor executor = Executors.newFixedThreadPool(MAX_SERVER_THREADS);
        Server server = new Server();
        setup(server, new Listener() {
            @Override
            public void received(final Connection connection, final Object message) {
                if (message instanceof RMIMessage) {
                    executor.execute(new Runnable() {
                        @Override
                        public void run() {
                            connection.sendTCP(((RMIMessage) message).invokeOn(apiImpl));
                        }
                    });
                }
            }
        });

        try {
            server.bind(tcpPort, udpPort);
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(-1);
        }
    }

    public static API connectToServer(String host, int tcpPort, int udpPort) {
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
                    RMIMessage RMIMessage = (RMIMessage) message;
                    apiCalls.remove(RMIMessage.ID).set(RMIMessage.args.get(0));
                }
            }

        });
        try {
            client.connect(CONNECTION_TIMEOUT_MS, host, tcpPort, udpPort);
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(-1);
        }
        return new ClientAPI(client);
    }

    public static <T> Future<T> createFuture(T result) {
        SettableFuture<T> ret = SettableFuture.create();
        ret.set(result);
        return ret;
    }

    public static <T> T getValueFromFuture(Future<T> future) {
        try {
            return future.get();
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    private static void setup(EndPoint endPoint, Listener listener) {
        Kryo kryo = endPoint.getKryo();
        kryo.register(RMIMessage.class);
        kryo.register(ArrayList.class);
        endPoint.addListener(listener);
        endPoint.start();
    }

    private static Future<Object> makeServerCall(Client client, String methodName, Object... args) {
        long id = CLIENT_REQUESTS.incrementAndGet();
        System.out.println("Total calls made: " + id);
        Future<Object> f = SettableFuture.create();
        apiCalls.put(id, (SettableFuture<Object>) f);
        connections.get(client).sendTCP(new RMIMessage(id, methodName, Lists.newArrayList(args)));
        return f;
    }

    public static Future<Object> testCall(Client client, String s) {
        return makeServerCall(client, "appendHello", s);
    }
}

class RMIMessage {

    final long ID;
    final String methodName;
    final List<?> args;

    private static final Map<String, Method> methodCache = Maps.newHashMap();

    public RMIMessage() {
        this(0, null, null);
    }

    RMIMessage(long ID, String methodName, List<?> args) {
        this.ID = ID;
        this.methodName = methodName;
        this.args = args;
    }

    public RMIMessage invokeOn(API api) {
        Object result = Kotha.getValueFromFuture((Future) call(api, methodName, args.toArray()));
        return new RMIMessage(ID, methodName, Lists.newArrayList(result));
    }

    private static Object call(API api, String methodName, Object... args) {
        Class<?>[] paramTypes = new Class[args.length];
        for (int i = 0; i < args.length; i++) {
            paramTypes[i] = args[i] == null ? null : args[i].getClass();
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
                        if (m.getName().equals(methodName)) {
                            Class<?>[] methodParameterTypes = m.getParameterTypes();
                            if (areParamsExtendable(paramTypes, methodParameterTypes)) {
                                method = m;
                                break;
                            }
                        }
                    }
                }
            }
        }

        methodCache.put(key, method);
        try {
            return method.invoke(api, args);
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    private static boolean areParamsExtendable(Class<?>[] classes, Class<?>[] superClasses) {
        for (int i = 0; i < classes.length; i++) {
            if (classes[i] != null && !Primitives.wrap(superClasses[i]).isAssignableFrom(Primitives.wrap(classes[i]))) {
                return false;
            }
        }
        return classes.length == superClasses.length;
    }
}
