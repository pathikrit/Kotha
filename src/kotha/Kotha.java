package kotha;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryonet.Client;
import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.EndPoint;
import com.esotericsoftware.kryonet.Listener;
import com.esotericsoftware.kryonet.Server;
import com.google.common.base.Stopwatch;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.net.HostAndPort;
import com.google.common.primitives.Primitives;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import de.javakaffee.kryoserializers.ArraysAsListSerializer;
import de.javakaffee.kryoserializers.ClassSerializer;
import de.javakaffee.kryoserializers.CollectionsEmptyListSerializer;
import de.javakaffee.kryoserializers.CollectionsEmptyMapSerializer;
import de.javakaffee.kryoserializers.CollectionsEmptySetSerializer;
import de.javakaffee.kryoserializers.CollectionsSingletonListSerializer;
import de.javakaffee.kryoserializers.CollectionsSingletonMapSerializer;
import de.javakaffee.kryoserializers.CollectionsSingletonSetSerializer;
import de.javakaffee.kryoserializers.CurrencySerializer;
import de.javakaffee.kryoserializers.GregorianCalendarSerializer;
import de.javakaffee.kryoserializers.JdkProxySerializer;
import de.javakaffee.kryoserializers.StringBufferSerializer;
import de.javakaffee.kryoserializers.StringBuilderSerializer;
import de.javakaffee.kryoserializers.SynchronizedCollectionsSerializer;
import de.javakaffee.kryoserializers.UnmodifiableCollectionsSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Currency;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public final class Kotha {

    private final static Logger log = LoggerFactory.getLogger(Kotha.class);

    private final static int CONNECTION_TIMEOUT_MS = 5 * 1000;
    private final static int MAX_SERVER_THREADS = 20;

    private final static AtomicLong CLIENTS = new AtomicLong();
    private final static AtomicLong CLIENT_REQUESTS = new AtomicLong();

    private final static Multimap<Long, Connection> clientConnections = HashMultimap.create();
    private final static Map<Long, SettableFuture<Object>> apiCalls = Maps.newConcurrentMap();
    private final static Map<Long, Stopwatch> timers = Maps.newConcurrentMap();
    private final static HashMap<String, Connection> serviceLookup = Maps.newHashMap();
    private final static Map<Method, String> methodSignatureCache = Maps.newHashMap();

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public static @interface NotImplemented {
    }

    private Kotha() {
    }

    public static void startServer(int tcpPort, final Class<?> apiImpl) {
        try {
            final Executor executor = Executors.newFixedThreadPool(MAX_SERVER_THREADS);
            Server server = new Server();
            setup(server, new Listener() {
                @Override
                public void received(final Connection connection, final Object message) {
                    super.received(connection, message);
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

                @Override
                public void connected(Connection connection) {
                    super.connected(connection);
                    List<String> services = Lists.newArrayList();
                    for (Method m : apiImpl.getDeclaredMethods()) {
                        if (!m.isAnnotationPresent(NotImplemented.class)) {
                            services.add(getMethodSignature(m));
                        }
                    }
                    connection.sendTCP(services);
                }
            });
            server.bind(tcpPort);
        } catch (Throwable t) {
            error("Could not start server", t);
        }
    }

    public static <T> T connectToServer(Class<T> apiClass, String address, String... addresses) {
        try {
            Set<String> servers = Sets.newHashSet(address);
            if (addresses != null) {
                Collections.addAll(servers, addresses);
            }

            final long clientId = CLIENTS.incrementAndGet();

            for (String server : servers) {
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
                                if (serviceLookup.put(method, connection) != null) {
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
                    error("Could not connect to " + server, t);
                }
            }

            return getRemoteCaller(clientId, apiClass);
        } catch (Throwable t) {
            return error("Could not start client", t);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T getRemoteCaller(final Long clientId, final Class<T> apiClass) {
        return (T) Proxy.newProxyInstance(apiClass.getClassLoader(), new Class[]{apiClass}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                final String methodSignature = getMethodSignature(method);
                final Connection connection = serviceLookup.get(methodSignature);
                if (connection == null || !clientConnections.get(clientId).contains(connection)) {
                    log.error("Could not find server implementing " + methodSignature);
                    return null;
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

    private static void setup(EndPoint endPoint, Listener listener) {
        Kryo kryo = endPoint.getKryo();
        kryo.register(RMIMessage.class);
        kryo.register(ArrayList.class);
        kryo.register(Object[].class);
        kryo.register(Arrays.asList("").getClass(), new ArraysAsListSerializer(kryo));
        kryo.register(Class.class, new ClassSerializer(kryo));
        kryo.register(Collections.EMPTY_LIST.getClass(), new CollectionsEmptyListSerializer());
        kryo.register(Collections.EMPTY_MAP.getClass(), new CollectionsEmptyMapSerializer());
        kryo.register(Collections.EMPTY_SET.getClass(), new CollectionsEmptySetSerializer());
        kryo.register(Collections.singletonList("").getClass(), new CollectionsSingletonListSerializer(kryo));
        kryo.register(Collections.singleton("").getClass(), new CollectionsSingletonSetSerializer(kryo));
        kryo.register(Collections.singletonMap("", "").getClass(), new CollectionsSingletonMapSerializer(kryo));
        kryo.register(Currency.class, new CurrencySerializer(kryo));
        kryo.register(GregorianCalendar.class, new GregorianCalendarSerializer());
        kryo.register(InvocationHandler.class, new JdkProxySerializer(kryo));
        kryo.register(StringBuffer.class, new StringBufferSerializer(kryo));
        kryo.register(StringBuilder.class, new StringBuilderSerializer(kryo));
        UnmodifiableCollectionsSerializer.registerSerializers(kryo);
        SynchronizedCollectionsSerializer.registerSerializers(kryo);
        endPoint.addListener(listener);
        endPoint.start();
    }

    private static <T> T error(String message, Throwable cause) {
        log.error(message, cause);
        return null;
    }

    private static String getMethodSignature(Method m) {
        String signature = methodSignatureCache.get(m);
        if (signature == null) {
            signature = m.getName() + "(";
            for (Class<?> p : m.getParameterTypes()) {
                signature += p.getName() + ",";
            }
            methodSignatureCache.put(m, signature += ")");
        }
        return signature;
    }
}

class RMIMessage {

    final long id;
    final String methodName;
    final Object[] args;

    final static transient Map<String, Method> methodCache = Maps.newHashMap();

    RMIMessage() {
        this(0, null);
    }

    RMIMessage(long id, String methodName, Object... args) {
        this.id = id;
        this.methodName = methodName;
        this.args = args;
    }

    RMIMessage invokeOn(Class<?> apiClass) {
        Class<?>[] paramTypes = new Class[args.length];
        for (int i = 0; i < args.length; i++) {
            paramTypes[i] = args[i] == null ? null : args[i].getClass();
        }

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

        Future<?> f;
        try {
            f = (Future<?>) method.invoke(apiClass.newInstance(), args);
        } catch (Throwable t) {
            ((SettableFuture<?>) (f = SettableFuture.create())).setException(t);
        }

        return new RMIMessage(id, methodName, Futures.getUnchecked(f));
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
