package cn.ken.shoes.util;

import java.util.List;
import java.util.concurrent.CountDownLatch;

public class AsyncUtil {

    public static void awaitTasks(List<Runnable> tasks) throws InterruptedException {
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
}
