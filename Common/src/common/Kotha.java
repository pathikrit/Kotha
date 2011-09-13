package common;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryonet.*;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.primitives.Primitives;
import com.google.common.util.concurrent.SettableFuture;
import javassist.*;

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
    // private final static Map<Client, CtClass> connections = Maps.newHashMap();

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
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(-1);
            return null;
        }

        return getApiStubForClient(client);
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

    static Future<?> makeServerCall(Client client, String methodName, Object... args) {
        long id = CLIENT_REQUESTS.incrementAndGet();
        Future<?> f = SettableFuture.create();
        apiCalls.put(id, (SettableFuture<Object>) f);
        connections.get(client).sendTCP(new RMIMessage(id, methodName, Lists.newArrayList(args)));
        return f;
    }

    private static API getApiStubForClient(Client client) {
        try {
            ClassPool classPool = ClassPool.getDefault();
            classPool.importPackage("common.API");
            classPool.importPackage("com.esotericsoftware.kryonet.Client");
            classPool.importPackage("common.Kotha");

            CtClass apiInterface = classPool.get("common.API");
            CtClass apiStub = classPool.makeClass("RemoteCaller", apiInterface);
            apiStub.addField(CtField.make("private Client client;", apiStub));

            CtConstructor ctConstructor = new CtConstructor(new CtClass[]{classPool.get("com.esotericsoftware.kryonet.Client")}, apiStub);
            ctConstructor.setBody("{ this.client = $1; }");
            apiStub.addConstructor(ctConstructor);

            for (CtMethod i : apiInterface.getDeclaredMethods()) {
                CtMethod remoteMethod = new CtMethod(i.getReturnType(), i.getName(), i.getParameterTypes(), apiStub);
                remoteMethod.setBody("{ return Kotha.makeServerCall(client, \"" + i.getName() + "\", null); }");
                remoteMethod.setModifiers(Modifier.PUBLIC);

                apiStub.addMethod(remoteMethod);

            }
            apiStub.setModifiers(Modifier.PUBLIC);
            apiStub.debugWriteFile("/Users/pathikritbhowmick/Desktop/");
            return (API) (apiStub.toClass().getConstructor(Client.class).newInstance(client));
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(-1);
            return null;

        }
    }
}

class RMIMessage {

    final long ID;
    final String methodName;
    final List<?> args;

    static final Map<String, Method> methodCache = Maps.newHashMap();

    RMIMessage() {
        this(0, null, null);
    }

    RMIMessage(long ID, String methodName, List<?> args) {
        this.ID = ID;
        this.methodName = methodName;
        this.args = args;
    }

    common.RMIMessage invokeOn(API api) {
        Object result = Kotha.getValueFromFuture((Future) call(api, methodName, args.toArray()));
        return new common.RMIMessage(ID, methodName, Lists.newArrayList(result));
    }

    static Object call(API api, String methodName, Object... args) {
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
            return method.invoke(api, args);
        } catch (Throwable t) {
            t.printStackTrace();
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