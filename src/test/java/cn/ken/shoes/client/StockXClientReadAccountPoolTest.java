package cn.ken.shoes.client;

import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.exception.StockXRateLimitException;
import cn.ken.shoes.model.stockx.StockXAccount;
import com.alibaba.fastjson.JSONObject;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockXClientReadAccountPoolTest {

    @Test
    void retriesAccountIndependentReadOnAnotherAccountInTheSameMarket() {
        List<StockXAccount> original = new ArrayList<>(StockXConfig.getAccounts());
        try {
            StockXAccount usA = account("us-a", "US");
            StockXAccount usB = account("us-b", "US");
            StockXAccount de = account("de-a", "DE");
            StockXConfig.setAccounts(List.of(usA, usB, de));
            StubStockXClient client = new StubStockXClient("us-a");

            JSONObject result = client.read("US", usA);

            assertThat(result.getJSONObject("data").getString("servedBy")).isEqualTo("us-b");
            assertThat(client.calls).containsExactly("us-a", "us-b");

            client.calls.clear();
            JSONObject second = client.read("US", usA);
            assertThat(second.getJSONObject("data").getString("servedBy")).isEqualTo("us-b");
            assertThat(client.calls).containsExactly("us-b");
        } finally {
            StockXConfig.setAccounts(original);
        }
    }

    @Test
    void usesConfiguredMarketPoolForLegacyReadWithoutPreferredAccount() {
        List<StockXAccount> original = new ArrayList<>(StockXConfig.getAccounts());
        try {
            StockXAccount usA = account("us-a", "US");
            StockXAccount usB = account("us-b", "US");
            StockXConfig.setAccounts(List.of(usA, usB));
            StubStockXClient client = new StubStockXClient("none");

            JSONObject result = client.read("US", null);

            assertThat(result.getJSONObject("data").getString("servedBy")).isEqualTo("us-a");
            assertThat(client.calls).containsExactly("us-a");
        } finally {
            StockXConfig.setAccounts(original);
        }
    }

    @Test
    void skipsAnAccountWhoseLocalOneQpsPermitIsBusy() {
        List<StockXAccount> original = new ArrayList<>(StockXConfig.getAccounts());
        try {
            StockXAccount usA = account("us-a", "US");
            StockXAccount usB = account("us-b", "US");
            StockXConfig.setAccounts(List.of(usA, usB));
            StubStockXClient client = new StubStockXClient("none");
            client.busyAccounts.add("us-a");

            JSONObject result = client.read("US", usA);

            assertThat(result.getJSONObject("data").getString("servedBy")).isEqualTo("us-b");
            assertThat(client.calls).containsExactly("us-b");
            assertThat(client.blockingPermitAcquires).isEmpty();
        } finally {
            StockXConfig.setAccounts(original);
        }
    }

    @Test
    void waitsOnTheRotatedFirstAccountOnlyWhenEveryLocalPermitIsBusy() {
        List<StockXAccount> original = new ArrayList<>(StockXConfig.getAccounts());
        try {
            StockXAccount usA = account("us-a", "US");
            StockXAccount usB = account("us-b", "US");
            StockXConfig.setAccounts(List.of(usA, usB));
            StubStockXClient client = new StubStockXClient("none");
            client.busyAccounts.addAll(List.of("us-a", "us-b"));

            JSONObject result = client.read("US", usA);

            assertThat(result.getJSONObject("data").getString("servedBy")).isEqualTo("us-a");
            assertThat(client.blockingPermitAcquires).containsExactly("us-a");
            assertThat(client.calls).containsExactly("us-a");
        } finally {
            StockXConfig.setAccounts(original);
        }
    }

    @Test
    void doesNotUseLegacyCredentialWhenConfiguredMarketAccountsAreAllCooling() {
        List<StockXAccount> original = new ArrayList<>(StockXConfig.getAccounts());
        try {
            StockXAccount hkA = account("hk-a", "HK");
            StockXAccount hkB = account("hk-b", "HK");
            StockXConfig.setAccounts(List.of(hkA, hkB));
            StubStockXClient client = new StubStockXClient("none");
            client.limitedAccounts.addAll(List.of("hk-a", "hk-b"));

            assertThatThrownBy(() -> client.read("HK", null))
                    .isInstanceOf(StockXRateLimitException.class);
            assertThatThrownBy(() -> client.read("HK", null))
                    .isInstanceOf(StockXRateLimitException.class);

            assertThat(client.legacyFallbackCalls).isZero();
        } finally {
            StockXConfig.setAccounts(original);
        }
    }

    @Test
    void doesNotReportEveryAccountAsLimitedWhenAnotherAccountIsUnauthorized() {
        List<StockXAccount> original = new ArrayList<>(StockXConfig.getAccounts());
        try {
            StockXAccount usA = account("us-a", "US");
            StockXAccount usB = account("us-b", "US");
            StockXConfig.setAccounts(List.of(usA, usB));
            StubStockXClient client = new StubStockXClient("none");
            client.limitedAccounts.add("us-a");
            client.unauthorizedAccounts.add("us-b");

            JSONObject result = client.read("US", null);

            assertThat(result.getString("message")).isEqualTo("Unauthorized");
        } finally {
            StockXConfig.setAccounts(original);
        }
    }

    private static StockXAccount account(String name, String country) {
        StockXAccount account = new StockXAccount();
        account.setName(name);
        account.setCountry(country);
        account.setAuthorization("Bearer " + name);
        account.setEnabled(true);
        return account;
    }

    private static class StubStockXClient extends StockXClient {
        private final List<String> calls = new ArrayList<>();
        private final List<String> blockingPermitAcquires = new ArrayList<>();
        private final Set<String> busyAccounts = new HashSet<>();
        private final Set<String> limitedAccounts = new HashSet<>();
        private final Set<String> unauthorizedAccounts = new HashSet<>();
        private final String limitedAccount;
        private int legacyFallbackCalls;

        private StubStockXClient(String limitedAccount) {
            this.limitedAccount = limitedAccount;
        }

        JSONObject read(String country, StockXAccount preferredAccount) {
            return queryReadPro("{\"operationName\":\"GetMarketData\"}", country, preferredAccount);
        }

        @Override
        protected boolean tryAcquireReadPermit(String accountName) {
            return !busyAccounts.contains(accountName);
        }

        @Override
        protected void acquireReadPermit(String accountName) {
            blockingPermitAcquires.add(accountName);
        }

        @Override
        protected JSONObject executeReadCandidate(String body, Headers headers, String accountName) {
            calls.add(accountName);
            if (limitedAccount.equals(accountName) || limitedAccounts.contains(accountName)) {
                throw new StockXRateLimitException(accountName, 0L);
            }
            if (unauthorizedAccounts.contains(accountName)) {
                return new JSONObject(true).fluentPut("message", "Unauthorized");
            }
            return new JSONObject(true).fluentPut("data",
                    new JSONObject(true).fluentPut("servedBy", accountName));
        }

        @Override
        protected JSONObject queryPro(String body, Headers headers, String accountName) {
            legacyFallbackCalls++;
            throw new AssertionError("configured market cooldown must not use legacy credentials");
        }
    }
}
