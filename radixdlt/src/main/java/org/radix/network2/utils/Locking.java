package org.radix.network2.utils;

import java.util.concurrent.locks.Lock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Provides helper methods that wrap calls to procedures and functions in an
 * exception safe way. These methods wrap procedure calls in
 * <code>lock(); try { ... } finally { unlock(); }</code> wrappers, avoiding
 * potentially erroneous locking paradigms.
 */
public final class Locking {
	private Locking() {
		throw new IllegalStateException("Can't construct");
	}

	/**
	 * Acquire a lock, execute a {@link Runnable} and finally unlock the lock.
	 *
	 * @param <A> The argument type.
	 * @param l   The lock to acquire/release.
	 * @param r   The {@link Runnable} to execute.
	 */
	public static <A> void withLock(Lock l, Runnable r) {
		l.lock();
		try {
			r.run();
		} finally {
			l.unlock();
		}
	}

	/**
	 * Acquire a lock, execute a {@link Consumer} and finally unlock the lock.
	 *
	 * @param <A> The argument type.
	 * @param l   The lock to acquire/release.
	 * @param c   The consumer to execute.
	 * @param arg The argument to pass to the consumer.
	 */
	public static <A> void withConsumerLock(Lock l, Consumer<A> c, A arg) {
		l.lock();
		try {
			c.accept(arg);
		} finally {
			l.unlock();
		}
	}

	/**
	 * Acquire a lock, execute a {@link BiConsumer} and finally unlock the lock.
	 *
	 * @param <A1> Argument 1 type.
	 * @param <A2> Argument 2 type.
	 * @param l    The lock to acquire/release.
	 * @param c    The consumer to execute.
	 * @param arg1 Argument 1 to pass to the consumer.
	 * @param arg2 Argument 2 to pass to the consumer.
	 */
	public static <A1, A2> void withConsumerLock(Lock l, BiConsumer<A1, A2> c, A1 arg1, A2 arg2) {
		l.lock();
		try {
			c.accept(arg1, arg2);
		} finally {
			l.unlock();
		}
	}

	/**
	 * Acquire a lock, execute a {@link Supplier} and finally unlock the lock, returning the
	 * result.
	 *
	 * @param <R> The result type from <code>r</code>.
	 * @param <A> The argument type for <code>r</code>.
	 * @param l   The lock to acquire/release.
	 * @param s   The supplier to execute.
	 * @return The result from the executed supplier.
	 */
	public static <R, A> R withSupplierLock(Lock l, Supplier<R> s) {
		l.lock();
		try {
			return s.get();
		} finally {
			l.unlock();
		}
	}

	/**
	 * Acquire a lock, execute a {@link Predicate} and finally unlock the lock, returning the
	 * result.
	 *
	 * @param <R> The result type from <code>r</code>.
	 * @param <A> The argument type for <code>r</code>.
	 * @param l   The lock to acquire/release.
	 * @param r   The function to execute.
	 * @param arg The argument to the function.
	 * @return The result from the executed function.
	 */
	public static <A> boolean withPredicateLock(Lock l, Predicate<A> p, A arg) {
		l.lock();
		try {
			return p.test(arg);
		} finally {
			l.unlock();
		}
	}

	/**
	 * Acquire a lock, execute a {@link Function} and finally unlock the lock, returning the
	 * result.
	 *
	 * @param <R> The result type from <code>r</code>.
	 * @param <A> The argument type for <code>r</code>.
	 * @param l   The lock to acquire/release.
	 * @param f   The function to execute.
	 * @param arg The argument to the function.
	 * @return The result from the executed function.
	 */
	public static <R, A> R withFunctionLock(Lock l, Function<A, R> f, A arg) {
		l.lock();
		try {
			return f.apply(arg);
		} finally {
			l.unlock();
		}
	}

}