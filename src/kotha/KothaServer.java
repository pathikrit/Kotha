package kotha;

import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Listener;
import com.esotericsoftware.kryonet.Server;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.primitives.Primitives;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static kotha.Kotha.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KothaServer {

    private final static Logger log = LoggerFactory.getLogger(KothaServer.class);
    private final static Map<String, Method> methodCache = Maps.newHashMap();

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public static @interface NotImplemented {
    }

    private final static int MAX_SERVER_THREADS = 20;

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
                                    connection.sendTCP(invoke(apiImpl, (RMIMessage) message));
                                } catch (Throwable t) {
                                    error(log, "Error in server api call", t);
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
                            final String methodSignature = getMethodSignature(m);
                            services.add(methodSignature);
                            log.info("This server implements " + methodSignature);
                        }
                    }
                    connection.sendTCP(services);
                }
            });
            server.bind(tcpPort);
        } catch (Throwable t) {
            error(log, "Could not start server", t);
        }
    }

    private static RMIMessage invoke(Class<?> apiClass, RMIMessage message) {
        final long id = message.id;
        final String methodName = message.methodName;
        final Object[] args = message.args;

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

    private static boolean areParamsExtendable(Class<?>[] classes, Class<?>[] superClasses) {
        for (int i = 0; i < classes.length; i++) {
            if (classes[i] != null && !Primitives.wrap(superClasses[i]).isAssignableFrom(Primitives.wrap(classes[i]))) {
                return false;
            }
        }
        return classes.length == superClasses.length;
    }
}
