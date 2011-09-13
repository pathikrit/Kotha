package common;

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

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtField;
import javassist.CtMethod;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
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

    private final static Constructor<? extends API> constructor;

    static {
        Constructor<? extends API> ret;
        try {
            final String apiPkg = "common.API", clientPkg = "com.esotericsoftware.kryonet.Client";
            ClassPool classPool = ClassPool.getDefault();
            classPool.importPackage(apiPkg);
            classPool.importPackage(clientPkg);
            classPool.importPackage("common.Kotha");

            CtClass apiInterface = classPool.get(apiPkg);
            CtClass apiStub = classPool.makeClass("common.RemoteCaller");
            apiStub.addInterface(apiInterface);
            apiStub.addField(CtField.make("private final Client client;", apiStub));

            CtConstructor ctConstructor = new CtConstructor(new CtClass[]{classPool.get(clientPkg)}, apiStub);
            ctConstructor.setBody("{ this.client = $1; }");
            apiStub.addConstructor(ctConstructor);

            for (CtMethod i : apiInterface.getDeclaredMethods()) {
                CtMethod remoteMethod = new CtMethod(i.getReturnType(), i.getName(), i.getParameterTypes(), apiStub);
                remoteMethod.setBody("{ return Kotha.makeServerCall(client, \"" + i.getName() + "\", $args); }");
                apiStub.addMethod(remoteMethod);
            }

            @SuppressWarnings("unchecked") Class<? extends API> apiStubClass = (Class<? extends API>) apiStub.toClass();
            ret = apiStubClass.getConstructor(Client.class);
        } catch (Throwable t) {
            ret = fatalExit("Could not generate Client API stub", t);
        }
        constructor = ret;
    }

    private Kotha() {
    }

    public static void startServer(int tcpPort, int udpPort, final API apiImpl) {
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
                                    log.error("Error in server api call", t);
                                }
                            }
                        });
                    }
                }
            });
            server.bind(tcpPort, udpPort);
        } catch (Throwable t) {
            fatalExit("Could not start server", t);
        }
    }

    public static API connectToServer(String host, int tcpPort, int udpPort) {
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
                        RMIMessage RMIMessage = (RMIMessage) message;
                        apiCalls.remove(RMIMessage.id).set(RMIMessage.args.get(0));
                    }
                }
            });

            client.connect(CONNECTION_TIMEOUT_MS, host, tcpPort, udpPort);
            return constructor.newInstance(client);
        } catch (Throwable t) {
            return fatalExit("Could not start client", t);
        }
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
            log.error("Could not get value from future", t);
            return null;
        }
    }

    static Future<Object> makeServerCall(Client client, String methodName, Object... args) {
        final long id = CLIENT_REQUESTS.incrementAndGet();
        SettableFuture<Object> future = SettableFuture.create();
        apiCalls.put(id, future);
        connections.get(client).sendTCP(new RMIMessage(id, methodName, Lists.newArrayList(args)));
        return future;
    }

    private static void setup(EndPoint endPoint, Listener listener) {
        Kryo kryo = endPoint.getKryo();
        kryo.register(RMIMessage.class);
        kryo.register(ArrayList.class);
        // TODO: More Kryo serializers: https://github.com/magro/kryo-serializers
        endPoint.addListener(listener);
        endPoint.start();
    }

    private static <T> T fatalExit(String message, Throwable cause) {
        log.error(message, cause);
        System.exit(-1);
        return null;
    }
}

class RMIMessage {

    final long id;
    final String methodName;
    final List<?> args; // TODO: just use Object array?

    final static transient Logger log = LoggerFactory.getLogger(RMIMessage.class);
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
        return new RMIMessage(id, methodName, Lists.newArrayList(call(api, args.toArray())));
    }

    Object call(API api, Object... args) {
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
            return Kotha.getValueFromFuture((Future<?>) method.invoke(api, args));
        } catch (Throwable t) {
            log.error("Could not invoke " + methodName, t);
            return null;
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
