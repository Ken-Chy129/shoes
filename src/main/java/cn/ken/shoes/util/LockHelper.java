package cn.ken.shoes.util;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public class LockHelper {

    private static final ReentrantLock KC_ITEM_LOCK = new ReentrantLock();

    private static final AtomicBoolean KC_ITEM_INITIALIZED = new AtomicBoolean(false);

    public static Boolean CLEAN_OLD = true;

    public static void setKcItemStatus(boolean status) {
        while (!KC_ITEM_INITIALIZED.compareAndSet(!status, status)) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static boolean isKcItemLock() {
        return KC_ITEM_LOCK.isLocked();
    }

    public static void lockKcItem() {
        KC_ITEM_LOCK.lock();
    }

    public static void unlockKcItem() {
        KC_ITEM_LOCK.unlock();
    }

    public static void lockPoisonPrice() {

    }
}
