package cn.ken.shoes.config;

import cn.ken.shoes.model.stockx.StockXAccount;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StockXConfigTest {

    @Test
    void keepsLegacyBatchFieldsWithoutUsingThemForLocalThrottling() {
        List<StockXAccount> original = new ArrayList<>(StockXConfig.getAccounts());
        StockXAccount legacy = account("legacy", 500);
        StockXAccount conservative = account("conservative", 400);

        try {
            StockXConfig.setAccounts(List.of(legacy, conservative));

            assertThat(StockXConfig.getAccount("legacy").getBatchItemLimit()).isEqualTo(500);
            assertThat(StockXConfig.getAccount("conservative").getBatchItemLimit()).isEqualTo(400);
        } finally {
            StockXConfig.setAccounts(original);
        }
    }

    @Test
    void newAccountsDoNotHaveALocalBatchItemQuota() {
        assertThat(new StockXAccount().getBatchItemLimit()).isZero();
    }

    private static StockXAccount account(String name, int batchItemLimit) {
        StockXAccount account = new StockXAccount();
        account.setName(name);
        account.setBatchItemLimit(batchItemLimit);
        return account;
    }
}
