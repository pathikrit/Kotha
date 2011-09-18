package kotha;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryonet.EndPoint;
import com.esotericsoftware.kryonet.Listener;

import com.google.common.collect.Maps;

import de.javakaffee.kryoserializers.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.*;

import org.slf4j.Logger;

class Kotha {

    static void setup(EndPoint endPoint, Listener listener) {
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

    static <T> T error(Logger log, String message, Throwable cause) {
        log.error(message, cause);
        return null;
    }

    private final static Map<Method, String> methodSignatureCache = Maps.newHashMap();

    static String getMethodSignature(Method m) {
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

    static class RMIMessage {

        final long id;
        final String methodName;
        final Object[] args;

        RMIMessage() {
            this(0, null);
        }

        RMIMessage(long id, String methodName, Object... args) {
            this.id = id;
            this.methodName = methodName;
            this.args = args;
        }
    }
}

