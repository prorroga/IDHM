package net.prorrogam.idhm.database;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class StorageManager implements AutoCloseable {

    private static final int POOL_SIZE = 2;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 60L;

    private final Storage storage;
    private final ExecutorService executor;
    private final Logger logger;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public StorageManager(Storage storage, Logger logger) {
        this.storage = storage;
        this.logger = logger;
        this.executor = Executors.newFixedThreadPool(POOL_SIZE, new StorageThreadFactory());
    }

    public <T> CompletableFuture<T> submit(Operation<T> operation) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return operation.execute(storage);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        executor.shutdown();

        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.warning("Storage executor did not terminate within "
                        + SHUTDOWN_TIMEOUT_SECONDS + "s; closing storage anyway.");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.log(Level.WARNING, "Interrupted while waiting for storage executor", e);
        }

        storage.close();
    }

    @FunctionalInterface
    public interface Operation<T> {
        T execute(Storage storage) throws Exception;
    }

    private static final class StorageThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "idhm-storage-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
