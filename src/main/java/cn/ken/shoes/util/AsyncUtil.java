package cn.ken.shoes.util;

import com.google.common.util.concurrent.RateLimiter;

import java.util.List;
import java.util.concurrent.CountDownLatch;

public class AsyncUtil {

    public static void runTasks(List<Runnable> tasks) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(tasks.size());
        for (Runnable task : tasks) {
            Thread.ofVirtual().start(() -> {
                try {
                    task.run();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
    }

    public static void runTasksWithLimit(List<Runnable> tasks, int permitsPerSecond) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(tasks.size());
        RateLimiter rateLimiter = RateLimiter.create(permitsPerSecond);
        for (Runnable task : tasks) {
            rateLimiter.acquire();
            Thread.ofVirtual().start(() -> {
                try {
                    task.run();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
    }
}
