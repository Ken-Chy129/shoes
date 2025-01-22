package cn.ken.shoes.util;

import com.google.common.util.concurrent.RateLimiter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

@Slf4j
public class AsyncUtil {

    public static void runTasks(List<Runnable> tasks) throws InterruptedException {
        ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
        for (Runnable task : tasks) {
            executorService.execute(task);
        }
    }

    @SneakyThrows
    public static void runTasksUntilFinish(List<Runnable> tasks) {
        ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<?>> futures = new ArrayList<>();
        for (Runnable task : tasks) {
            futures.add(executorService.submit(task));
        }
        for (Future<?> future : futures) {
            future.get();
        }
    }

    public static void runTasks(List<Runnable> tasks, int permitsPerSecond) throws InterruptedException {
        ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
        RateLimiter limiter = RateLimiter.create(permitsPerSecond);
        for (Runnable task : tasks) {
            limiter.acquire();
            executorService.execute(task);
        }
    }

    @SneakyThrows
    public static void runTasksUntilFinish(List<Runnable> tasks, int permitsPerSecond) {
        ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<?>> futures = new ArrayList<>();
        RateLimiter limiter = RateLimiter.create(permitsPerSecond);
        for (Runnable task : tasks) {
            limiter.acquire();
            futures.add(executorService.submit(task));
        }
        for (Future<?> future : futures) {
            future.get();
        }
    }

    public static <T> List<T> runTasksWithResult(List<Callable<T>> callables, int permitsPerSecond) {
        ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<T>> futures = new ArrayList<>();
        RateLimiter limiter = RateLimiter.create(permitsPerSecond);
        for (Callable<T> callable : callables) {
            limiter.acquire();
            futures.add(executorService.submit(callable));
        }
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            try {
                results.add(future.get());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return results;
    }

    public static <T> List<T> runTasksWithResult(List<Callable<T>> callables) {
        ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> callable : callables) {
            futures.add(executorService.submit(callable));
        }
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            try {
                results.add(future.get());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return results;
    }
}
