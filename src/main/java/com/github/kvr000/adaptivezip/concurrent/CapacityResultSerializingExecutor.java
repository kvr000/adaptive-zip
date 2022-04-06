package com.github.kvr000.adaptivezip.concurrent;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;


/**
 * Executor serializing the results and controlling pending items by capacity and count.
 */
public class CapacityResultSerializingExecutor implements AutoCloseable
{
	public CapacityResultSerializingExecutor(long capacity, long count)
	{
		this.capacity = capacity;
		this.count = count;
		this.orderedTasks = new LinkedBlockingDeque<>();
	}

	public <T> CompletableFuture<T> submit(long capacity, Callable<T> callable)
	{
		ExecutionFuture<T> future = new ExecutionFuture<>(capacity);
		addFuture(capacity, future);
		future.execute(callable);
		return future.wrapping;
	}

	public <T> CompletableFuture<T> submit(long capacity, Callable<T> callable, Executor executor)
	{
		ExecutionFuture<T> future = new ExecutionFuture<>(capacity);
		addFuture(capacity, future);
		future.execute(callable, executor);
		return future.wrapping;
	}

	@Override
	public void close() throws InterruptedException
	{
		synchronized (isEmpty) {
			if (!orderedTasks.isEmpty())
				isEmpty.wait();
		}
	}

	private synchronized void addFuture(long capacity, ExecutionFuture<?> future)
	{
		for (;;) {
			if ((this.count <= 0 || capacity > this.capacity) && !orderedTasks.isEmpty()) {
				try {
					this.wait();
				}
				catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
			}
			else {
				this.capacity -= capacity;
				this.count -= 1;
				try {
					orderedTasks.put(future);
				}
				catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
				break;
			}
		}
	}

	@SuppressWarnings("unchecked")
	private void processPending(ExecutionFuture<?> future)
	{
		if (orderedTasks.peek() != future)
			return;
		for (;;) {
			if (!PROCESSING_PENDING_UPDATER.compareAndSet(this, 0, 1))
				return;

			for (;;) {
				ExecutionFuture<Object> item = (ExecutionFuture<Object>) orderedTasks.peek();
				if (item == null || !item.isDone()) {
					PROCESSING_PENDING_UPDATER.set(this, 0);
					item = (ExecutionFuture<Object>) orderedTasks.peek();
					if (item != null && item.isDone()) {
						break;
					}
					if (item == null) {
						synchronized (isEmpty) {
							isEmpty.notify();
						}
					}
					return;
				}
				try {
					item.wrapping.complete(item.get());
				}
				catch (ExecutionException e) {
					item.wrapping.completeExceptionally(e.getCause());
				}
				catch (InterruptedException e) {
					item.wrapping.completeExceptionally(e);
				}
				finally {
					synchronized (this) {
						this.capacity += item.capacity;
						this.count += 1;
						this.notify();
					}
					orderedTasks.remove();
				}
			}
		}
	}

	private class ExecutionFuture<T> extends CompletableFuture<T>
	{
		private final long capacity;

		private CompletableFuture<Void> underlying;

		private final CompletableFuture<T> wrapping = new CompletableFuture<T>() {
			@Override
			public boolean cancel(boolean interrupt)
			{
				return underlying.cancel(interrupt);
			}
		};

		public ExecutionFuture(long capacity)
		{
			this.capacity = capacity;
		}

		public void execute(Callable<T> callable)
		{
			underlying = CompletableFuture.runAsync(() -> {
				try {
					complete(callable.call());
				}
				catch (Throwable e) {
					completeExceptionally(e);
				}
				finally {
					processPending(ExecutionFuture.this);
				}
			});
		}

		public void execute(Callable<T> callable, Executor executor)
		{
			underlying = CompletableFuture.runAsync(() -> {
				try {
					complete(callable.call());
				}
				catch (Throwable e) {
					completeExceptionally(e);
				}
				finally {
					processPending(ExecutionFuture.this);
				}
			}, executor);
		}
	}

	private long capacity;

	private long count;

	private final LinkedBlockingDeque<ExecutionFuture<?>> orderedTasks;

	private volatile int processingPending = 0;

	private final Object isEmpty = new Object();

	private static final AtomicIntegerFieldUpdater<CapacityResultSerializingExecutor>  PROCESSING_PENDING_UPDATER =
		AtomicIntegerFieldUpdater.newUpdater(CapacityResultSerializingExecutor.class, "processingPending");
}
