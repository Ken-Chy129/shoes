package cn.ken.shoes.manager;

import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.model.stockx.StockXAccount;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class StockXReadAccountPoolTest {

    @Test
    void rotatesAcrossEnabledAccountsInTheSameMarket() {
        List<StockXAccount> original = new ArrayList<>(StockXConfig.getAccounts());
        try {
            StockXAccount usA = account("us-a", "US", true);
            StockXAccount usB = account("us-b", "us", true);
            StockXAccount eu = account("eu-a", "DE", true);
            StockXAccount disabled = account("us-disabled", "US", false);
            StockXConfig.setAccounts(List.of(usA, usB, eu, disabled));

            StockXReadAccountPool pool = new StockXReadAccountPool();

            assertThat(names(pool.candidates("US", usA))).containsExactly("us-a", "us-b");
            assertThat(names(pool.candidates("US", usA))).containsExactly("us-b", "us-a");
        } finally {
            StockXConfig.setAccounts(original);
        }
    }

    @Test
    void skipsRateLimitedAccountUntilItsReadCooldownExpires() {
        List<StockXAccount> original = new ArrayList<>(StockXConfig.getAccounts());
        AtomicLong now = new AtomicLong(1_000L);
        try {
            StockXAccount usA = account("us-a", "US", true);
            StockXAccount usB = account("us-b", "US", true);
            StockXConfig.setAccounts(List.of(usA, usB));
            StockXReadAccountPool pool = new StockXReadAccountPool(now::get, 300_000L);

            pool.markRateLimited("us-a");
            assertThat(names(pool.candidates("US", usA))).containsExactly("us-b");

            now.addAndGet(300_001L);
            assertThat(names(pool.candidates("US", usA))).containsExactlyInAnyOrder("us-a", "us-b");
        } finally {
            StockXConfig.setAccounts(original);
        }
    }

    @Test
    void anOlderSuccessfulRequestDoesNotClearANewerRateLimitCooldown() {
        List<StockXAccount> original = new ArrayList<>(StockXConfig.getAccounts());
        AtomicLong now = new AtomicLong(1_000L);
        try {
            StockXAccount usA = account("us-a", "US", true);
            StockXAccount usB = account("us-b", "US", true);
            StockXConfig.setAccounts(List.of(usA, usB));
            StockXReadAccountPool pool = new StockXReadAccountPool(now::get, 300_000L);

            pool.markRateLimited("us-a");
            pool.markSuccess("us-a");

            assertThat(names(pool.candidates("US", usA))).containsExactly("us-b");
        } finally {
            StockXConfig.setAccounts(original);
        }
    }

    @Test
    void reportsConfiguredMarketSeparatelyFromCurrentlyAvailableAccounts() {
        List<StockXAccount> original = new ArrayList<>(StockXConfig.getAccounts());
        try {
            StockXAccount hkA = account("hk-a", "HK", true);
            StockXConfig.setAccounts(List.of(hkA));
            StockXReadAccountPool pool = new StockXReadAccountPool();
            pool.markRateLimited("hk-a");

            StockXReadAccountPool.Selection selection = pool.selection("HK", null);

            assertThat(selection.configuredMarketPool()).isTrue();
            assertThat(selection.candidates()).isEmpty();
        } finally {
            StockXConfig.setAccounts(original);
        }
    }

    @Test
    void keepsTheTaskAccountAsCompatibilityFallbackWhenNoPoolIsConfigured() {
        List<StockXAccount> original = new ArrayList<>(StockXConfig.getAccounts());
        try {
            StockXConfig.setAccounts(List.of());
            StockXAccount taskAccount = account("task-account", "US", false);

            StockXReadAccountPool pool = new StockXReadAccountPool();

            assertThat(names(pool.candidates("US", taskAccount))).containsExactly("task-account");
        } finally {
            StockXConfig.setAccounts(original);
        }
    }

    private static List<String> names(List<StockXAccount> accounts) {
        return accounts.stream().map(StockXAccount::getName).toList();
    }

    private static StockXAccount account(String name, String country, boolean enabled) {
        StockXAccount account = new StockXAccount();
        account.setName(name);
        account.setCountry(country);
        account.setAuthorization("Bearer " + name);
        account.setEnabled(enabled);
        return account;
    }
}
