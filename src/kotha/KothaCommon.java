package kotha;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Currency;
import java.util.GregorianCalendar;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.serialize.SimpleSerializer;
import com.esotericsoftware.kryonet.EndPoint;
import com.esotericsoftware.kryonet.Listener;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;

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

public class KothaCommon {

	/**
	 * Interface for APIResult class
	 * @param <T> result type
	 * @param <E> exception type
	 */
	public static interface APIResult<T, E extends Exception> {
		public T get() throws InterruptedException, E;
		public T get(long timeout, TimeUnit unit) throws InterruptedException, E;
		public T blockingGet() throws InterruptedException, E;
	}
	
	/**
	 * Implementation of the APIResult
	 * @param <T> result type
	 */
	public static class APIResultImpl<T> implements APIResult<T, Exception>{
		AtomicReference<T> result = new AtomicReference<T>(null);
		AtomicReference<Exception> exception = new AtomicReference<Exception>(null);
		CountDownLatch done = new CountDownLatch(1);
		
		public APIResultImpl() {}
		public APIResultImpl(T result) {
			done(result);
		}
		
		protected void done(T result) {
			done(result, null);
		}
		
		protected void done(Exception exception) {
			done(null, exception);
		}
		
		private synchronized void done(T result, Exception exception) {
			if(isDone()) throw new IllegalStateException("Done called twice");
			if(result != null && exception != null) throw new IllegalArgumentException("Result and exception both cannot be null");
			
			this.result.set(result);
			this.exception.set(exception);
			done.countDown();
		}
		
		public boolean isDone() { return done.getCount() == 0; }
		
		public T get(long timeout, TimeUnit unit) throws InterruptedException, Exception {
			done.await(timeout, unit);			
			return get();
		}
		
		public T blockingGet() throws InterruptedException, Exception {
			done.await();
			return get();
		}

		public T get() throws InterruptedException, Exception {
			if(!isDone()) return null;
			
			if(exception.get() != null) throw exception.get();
			return result.get();
		}
		
		@Override
		public String toString() {
			return "APIResultImpl [" +
					"result=" + result + ", " +
					"exception="+ exception + ", " +
					"resultIn=" + done + 
					"]";
		}
	}
	
	static class CountDownLatchSerializer extends SimpleSerializer<CountDownLatch> {
		private final Kryo _kryo;
	    
	    public CountDownLatchSerializer( final Kryo kryo ) {
	        _kryo = kryo;
	    }
	    
		@Override
		public CountDownLatch read(ByteBuffer buffer) {
			final int count = _kryo.readObject( buffer, Integer.class );
			return new CountDownLatch(count);
		}

		@Override
		public void write(ByteBuffer buffer, CountDownLatch countDownLatch) {
			 _kryo.writeClassAndObject( buffer, (int)countDownLatch.getCount() );
		}
	}
	
    static void setup(EndPoint endPoint, Listener listener) {
        Kryo kryo = endPoint.getKryo();
        kryo.register(RMIMessage.class);
        kryo.register(APIResultImpl.class);
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
        kryo.register(AtomicReference.class);
        kryo.register(CountDownLatch.class, new CountDownLatchSerializer(kryo));
        UnmodifiableCollectionsSerializer.registerSerializers(kryo);
        SynchronizedCollectionsSerializer.registerSerializers(kryo);
        endPoint.addListener(listener);
        endPoint.start();
    }

    static <T> T error(Logger log, String message, Throwable cause) {
        log.error(message, cause);
        return null;
    }

    static Cache<Method, String> methodSignatureCache = CacheBuilder.newBuilder().build(new CacheLoader<Method, String>() {
        @Override
        public String load(Method m) throws Exception {
            String signature = m.getName() + "(";
            for (Class<?> p : m.getParameterTypes()) {
                signature += p.getName() + ",";
            }
            return signature;
        }
    });

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

