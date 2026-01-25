package cn.ken.shoes.util;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public class LockHelper {

    private static final ReentrantLock KC_ITEM_LOCK = new ReentrantLock();

    private static final AtomicBoolean KC_ITEM_INITIALIZED = new AtomicBoolean(false);

    private static final ReentrantLock POISON_PRICE_LOCK = new ReentrantLock();

    private static final ReentrantLock STOCKX_ITEM_LOCK = new ReentrantLock();

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

    public static boolean isPoisonPriceLock() {
        return POISON_PRICE_LOCK.isLocked();
    }

    public static void lockPoisonPrice() {
        POISON_PRICE_LOCK.lock();
    }

    public static void unlockPoisonPrice() {
        POISON_PRICE_LOCK.unlock();
    }

    public static boolean isStockXItemLock() {
        return STOCKX_ITEM_LOCK.isLocked();
    }

    public static void lockStockXItem() {
        STOCKX_ITEM_LOCK.lock();
    }

    public static void unlockStockXItem() {
        STOCKX_ITEM_LOCK.unlock();
    }
}
