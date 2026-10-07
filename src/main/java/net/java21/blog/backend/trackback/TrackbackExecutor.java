package net.java21.blog.backend.trackback;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.DisposableBean;

/**
 * 트랙백 보내기 전용 스레드 풀(005 research M15, {@code blog.trackback.executor-threads}·{@code executor-queue}, 기본 2·100).
 * 대기열이 차면 받지 않고 {@link #trySubmit}이 false를 돌려준다(호출하는 쪽이 FAILED {@code REMOTE_ERROR} "Queue full"로 남긴다).
 * {@link java.util.concurrent.Executor} 빈으로 두지 않아 Boot 기본 {@code applicationTaskExecutor}(메일 {@code @Async})가 그대로 만들어진다.
 */
public class TrackbackExecutor implements DisposableBean {

    private final ThreadPoolExecutor pool;

    public TrackbackExecutor(int threads, int queueCapacity) {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "trackback-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.pool = new ThreadPoolExecutor(threads, threads, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    /** 맡기면 true, 대기열이 차거나 멈춘 뒤면 false. */
    public boolean trySubmit(Runnable task) {
        try {
            pool.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    @Override
    public void destroy() throws InterruptedException {
        pool.shutdown();
        if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
            pool.shutdownNow();
        }
    }
}
