package cn.ken.shoes.service;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.ListingFetchMode;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.model.stockx.StockXAccount;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class StockXServiceListingFetchModeTest {

    @Test
    void excelSearchQueriesEachDistinctStyleIdWithoutScanningAllListings() throws Exception {
        String accountName = "excel-search-account";
        StockXAccount account = new StockXAccount();
        account.setName(accountName);
        account.setCountry("HK");
        ShoesContext.getPriceDownMap(accountName, "STANDARD").put(
                "IF4396-104:9", new ShoesContext.PriceDownConfig(100, false));
        ShoesContext.getPriceDownMap(accountName, "STANDARD").put(
                "IF4396-104:10", new ShoesContext.PriceDownConfig(110, false));
        ShoesContext.getPriceDownMap(accountName, "STANDARD").put(
                "HF1234-001:8", new ShoesContext.PriceDownConfig(120, false));
        ShoesContext.getPriceDownMap(accountName, "STANDARD").put(
                "SKIP-ONLY:7", new ShoesContext.PriceDownConfig(-1, true));

        AtomicInteger fullScanCalls = new AtomicInteger();
        List<String> searchedStyleIds = new ArrayList<>();
        StockXClient client = new StockXClient() {
            @Override
            public JSONObject querySellingItemsByInventoryType(String inventoryType, Integer pageNumber,
                                                               StockXAccount ignored) {
                fullScanCalls.incrementAndGet();
                return emptyPage();
            }

            @Override
            public JSONObject querySellingItemsByStyleId(String inventoryType, Integer pageNumber,
                                                         String styleId, StockXAccount ignored) {
                searchedStyleIds.add(styleId);
                return pageWithFuzzyMatch(styleId + "-ALT");
            }
        };
        StockXService service = new StockXService();
        setField(service, "stockXClient", client);
        TaskSwitch.resetExcelCancel(accountName, "STANDARD");

        try {
            service.priceDownWithExcelForAccount(account, "STANDARD", ListingFetchMode.EXCEL_SEARCH);

            assertThat(fullScanCalls).hasValue(0);
            assertThat(searchedStyleIds).containsExactlyInAnyOrder("IF4396-104", "HF1234-001");
        } finally {
            ShoesContext.getPriceDownMap(accountName, "STANDARD").clear();
            TaskSwitch.clearExcelState(accountName, "STANDARD");
        }
    }

    @Test
    void excelSearchEndsCurrentRoundWithoutThrowingWhenQueryTemporarilyFails() throws Exception {
        String accountName = "excel-search-query-failure-account";
        StockXAccount account = new StockXAccount();
        account.setName(accountName);
        account.setCountry("HK");
        ShoesContext.getPriceDownMap(accountName, "STANDARD").put(
                "AAA-001:9", new ShoesContext.PriceDownConfig(100, false));
        ShoesContext.getPriceDownMap(accountName, "STANDARD").put(
                "BBB-002:10", new ShoesContext.PriceDownConfig(110, false));

        AtomicInteger searchCalls = new AtomicInteger();
        StockXClient client = new StockXClient() {
            @Override
            public JSONObject querySellingItemsByStyleId(String inventoryType, Integer pageNumber,
                                                         String styleId, StockXAccount ignored) {
                searchCalls.incrementAndGet();
                return null;
            }
        };
        StockXService service = new StockXService();
        setField(service, "stockXClient", client);
        TaskSwitch.resetExcelCancel(accountName, "STANDARD");

        try {
            assertThatCode(() -> service.priceDownWithExcelForAccount(
                    account, "STANDARD", ListingFetchMode.EXCEL_SEARCH))
                    .doesNotThrowAnyException();

            assertThat(searchCalls).hasValue(1);
        } finally {
            ShoesContext.getPriceDownMap(accountName, "STANDARD").clear();
            TaskSwitch.clearExcelState(accountName, "STANDARD");
        }
    }

    @Test
    void excelSearchStopsAtPerStylePageLimitWithoutProcessingMorePages() throws Exception {
        String accountName = "excel-search-page-limit-account";
        StockXAccount account = new StockXAccount();
        account.setName(accountName);
        account.setCountry("HK");
        ShoesContext.getPriceDownMap(accountName, "STANDARD").put(
                "AAA-001:9", new ShoesContext.PriceDownConfig(100, false));

        AtomicInteger searchCalls = new AtomicInteger();
        StockXClient client = new StockXClient() {
            @Override
            public JSONObject querySellingItemsByStyleId(String inventoryType, Integer pageNumber,
                                                         String styleId, StockXAccount ignored) {
                int currentCall = searchCalls.incrementAndGet();
                return pageWithHasMore(currentCall <= 20);
            }
        };
        StockXService service = new StockXService();
        setField(service, "stockXClient", client);
        TaskSwitch.resetExcelCancel(accountName, "STANDARD");

        try {
            service.priceDownWithExcelForAccount(account, "STANDARD", ListingFetchMode.EXCEL_SEARCH);

            assertThat(searchCalls).hasValue(20);
        } finally {
            ShoesContext.getPriceDownMap(accountName, "STANDARD").clear();
            TaskSwitch.clearExcelState(accountName, "STANDARD");
        }
    }

    private static JSONObject emptyPage() {
        return new JSONObject(true)
                .fluentPut("hasMore", false)
                .fluentPut("items", List.of());
    }

    private static JSONObject pageWithFuzzyMatch(String styleId) {
        return new JSONObject(true)
                .fluentPut("hasMore", false)
                .fluentPut("items", List.of(new JSONObject(true).fluentPut("styleId", styleId)));
    }

    private static JSONObject pageWithHasMore(boolean hasMore) {
        return new JSONObject(true)
                .fluentPut("hasMore", hasMore)
                .fluentPut("items", List.of());
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = StockXService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
