package cn.ken.shoes.purchase;

import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.manager.PriceManager;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.excel.ModelNoSearchExcel;
import cn.ken.shoes.model.excel.StockXPriceExcel;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.model.stockx.StockXSale;
import cn.ken.shoes.task.StockXPurchaseGuidanceTaskRunner;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StockXPurchaseGuidanceTaskRunnerTest {

    @Test
    void storesGuidanceForTheRequestedSizeAndKeepsMissingWindowsNull() {
        List<TaskItemDO> inserted = new ArrayList<>();
        TaskItemMapper itemMapper = mapper(TaskItemMapper.class, (method, args) -> {
            if ("insert".equals(method)) {
                inserted.add((TaskItemDO) args[0]);
                return 1;
            }
            return null;
        });
        TaskMapper taskMapper = mapper(TaskMapper.class, (method, args) -> null);
        StockXClient client = new StockXClient() {
            @Override
            public List<StockXPriceExcel> searchExactItemWithPrice(String modelNo, String type,
                                                                   String country, StockXAccount account) {
                StockXPriceExcel row = new StockXPriceExcel();
                row.setId("variant-1");
                row.setModelNo("DD1391-100");
                row.setTitle("Nike Dunk Low Panda");
                row.setUsmSize("10");
                row.setEuSize("44");
                row.setPurchasePrice(89);
                row.setStandardPrice(110);
                row.setFlexPrice(115);
                row.setAveragePrice90Days(new BigDecimal("96"));
                row.setSalesCount90Days(40);
                return List.of(row);
            }

            @Override
            public List<StockXSale> queryVariantSales(String variantId, StockXAccount account) {
                return List.of(new StockXSale(new BigDecimal("96"), Instant.now().minusSeconds(86400)));
            }
        };
        StockXAccount account = new StockXAccount();
        account.setName("account-a");
        account.setCountry("US");

        new StockXPurchaseGuidanceTaskRunner(account, 12L,
                List.of(input("DD1391-100", "US 10")), client, taskMapper, itemMapper).run();

        assertThat(inserted).singleElement().satisfies(item -> {
            assertThat(item.getProductId()).isEqualTo("variant-1");
            assertThat(item.getHighestBidPrice()).isEqualByComparingTo("89");
            assertThat(item.getAverageSalePrice30d()).isEqualByComparingTo("96");
            assertThat(item.getAverageSalePrice90d()).isEqualByComparingTo("96");
            assertThat(item.getRecommendedBid()).isEqualByComparingTo("90");
            assertThat(item.getOperateResult()).contains("90天成交中位价");
        });
    }

    @Test
    void attachesPoisonPriceByStyleIdAndEuSize() {
        List<TaskItemDO> inserted = new ArrayList<>();
        TaskItemMapper itemMapper = mapper(TaskItemMapper.class, (method, args) -> {
            if ("insert".equals(method)) {
                inserted.add((TaskItemDO) args[0]);
                return 1;
            }
            return null;
        });
        TaskMapper taskMapper = mapper(TaskMapper.class, (method, args) -> null);
        StockXClient client = new StockXClient() {
            @Override
            public List<StockXPriceExcel> searchExactItemWithPrice(String modelNo, String type,
                                                                   String country, StockXAccount account) {
                StockXPriceExcel row = new StockXPriceExcel();
                row.setId("variant-1");
                row.setModelNo("DD1391-100");
                row.setUsmSize("10");
                row.setEuSize("44");
                return List.of(row);
            }

            @Override
            public List<StockXSale> queryVariantSales(String variantId, StockXAccount account) {
                return List.of();
            }
        };
        PriceManager priceManager = new PriceManager() {
            @Override
            public Integer getPoisonPrice(String modelNo, String euSize) {
                return "DD1391-100".equals(modelNo) && "44".equals(euSize) ? 699 : null;
            }
        };
        StockXAccount account = new StockXAccount();
        account.setName("account-a");

        new StockXPurchaseGuidanceTaskRunner(account, 13L, List.of(input("DD1391-100", "US 10")),
                client, priceManager, taskMapper, itemMapper).run();

        assertThat(inserted).singleElement().satisfies(item ->
                assertThat(item.getPoisonPrice()).isEqualByComparingTo("699"));
    }

    @Test
    void returnsAllVariantsWithIdsWhenRequestedSizeCannotBeMatched() {
        List<TaskItemDO> inserted = new ArrayList<>();
        TaskItemMapper itemMapper = mapper(TaskItemMapper.class, (method, args) -> {
            if ("insert".equals(method)) {
                inserted.add((TaskItemDO) args[0]);
                return 1;
            }
            return null;
        });
        TaskMapper taskMapper = mapper(TaskMapper.class, (method, args) -> null);
        StockXClient client = new StockXClient() {
            @Override
            public List<StockXPriceExcel> searchExactItemWithPrice(String modelNo, String type,
                                                                   String country, StockXAccount account) {
                return List.of(apparel("variant-s", "S"), apparel("variant-m", "M"));
            }

            @Override
            public List<StockXSale> queryVariantSales(String variantId, StockXAccount account) {
                return List.of();
            }
        };
        StockXAccount account = new StockXAccount();
        account.setName("account-a");

        new StockXPurchaseGuidanceTaskRunner(account, 14L,
                List.of(input("HOODIE-1", "EU36"), input("HOODIE-1", "EU37")), client, taskMapper, itemMapper).run();

        assertThat(inserted).extracting(TaskItemDO::getProductId).containsExactly("variant-s", "variant-m");
        assertThat(inserted).extracting(TaskItemDO::getSize).containsExactly("S", "M");
        assertThat(inserted).allSatisfy(item -> assertThat(item.getOperateResult()).startsWith("尺码未匹配"));
    }

    @Test
    void matchesApparelLetterSizeByStockXLabel() {
        List<TaskItemDO> inserted = new ArrayList<>();
        TaskItemMapper itemMapper = mapper(TaskItemMapper.class, (method, args) -> {
            if ("insert".equals(method)) {
                inserted.add((TaskItemDO) args[0]);
                return 1;
            }
            return null;
        });
        StockXClient client = new StockXClient() {
            @Override
            public List<StockXPriceExcel> searchExactItemWithPrice(String modelNo, String type,
                                                                   String country, StockXAccount account) {
                return List.of(apparel("variant-s", "S"), apparel("variant-xl", "XL"));
            }

            @Override
            public List<StockXSale> queryVariantSales(String variantId, StockXAccount account) {
                return List.of();
            }
        };
        StockXAccount account = new StockXAccount();
        account.setName("account-a");

        new StockXPurchaseGuidanceTaskRunner(account, 15L, List.of(input("HOODIE-1", "xl")), client,
                mapper(TaskMapper.class, (method, args) -> null), itemMapper).run();

        assertThat(inserted).singleElement().satisfies(item -> {
            assertThat(item.getProductId()).isEqualTo("variant-xl");
            assertThat(item.getSize()).isEqualTo("XL");
        });
    }

    @Test
    void keywordModeWalksSortsAndPagesAndDeduplicatesVariants() {
        List<TaskItemDO> inserted = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        java.util.concurrent.atomic.AtomicReference<String> status = new java.util.concurrent.atomic.AtomicReference<>();
        TaskItemMapper itemMapper = mapper(TaskItemMapper.class, (method, args) -> {
            if ("insert".equals(method)) {
                inserted.add((TaskItemDO) args[0]);
                return 1;
            }
            return null;
        });
        TaskMapper taskMapper = mapper(TaskMapper.class, (method, args) -> {
            if ("updateTaskStatus".equals(method)) status.set((String) args[1]);
            if ("updateTaskFailed".equals(method)) status.set("failed:" + args[1]);
            return null;
        });
        StockXClient client = new StockXClient() {
            @Override
            public cn.hutool.core.lang.Pair<Integer, List<StockXPriceExcel>> searchItemWithPrice(
                    String query, Integer pageIndex, String sort, String searchType, String country,
                    StockXAccount account) {
                calls.add(query + "|" + sort + "|" + pageIndex + "|" + searchType);
                if (pageIndex == 1) {
                    return cn.hutool.core.lang.Pair.of(2, List.of(apparel("variant-s", "S"), apparel("variant-m", "M")));
                }
                // 第2页与第1页有重复尺码，应去重；第3页不应再请求（总页数=2）。
                return cn.hutool.core.lang.Pair.of(2, List.of(apparel("variant-m", "M"), apparel("variant-l", "L")));
            }

            @Override
            public List<StockXSale> queryVariantSales(String variantId, StockXAccount account) {
                return List.of();
            }
        };
        StockXAccount account = new StockXAccount();
        account.setName("account-a");

        new StockXPurchaseGuidanceTaskRunner(account, 16L,
                new StockXPurchaseGuidanceTaskRunner.KeywordQuery(List.of("hoodie"),
                        List.of("featured", "most-active"), 5, "clothes"),
                client, null, taskMapper, itemMapper).run();

        assertThat(calls).containsExactly(
                "hoodie|featured|1|clothes", "hoodie|featured|2|clothes",
                "hoodie|most-active|1|clothes", "hoodie|most-active|2|clothes");
        assertThat(inserted).extracting(TaskItemDO::getProductId)
                .containsExactly("variant-s", "variant-m", "variant-l");
        assertThat(inserted).extracting(TaskItemDO::getSize).containsExactly("S", "M", "L");
        assertThat(inserted).extracting(TaskItemDO::getStyleId).containsOnly("HOODIE-1");
        assertThat(status.get()).isEqualTo("success");
    }

    @Test
    void keywordModeFailsWhenSearchReturnsNothing() {
        java.util.concurrent.atomic.AtomicReference<String> failure = new java.util.concurrent.atomic.AtomicReference<>();
        TaskMapper taskMapper = mapper(TaskMapper.class, (method, args) -> {
            if ("updateTaskFailed".equals(method)) failure.set((String) args[1]);
            return null;
        });
        StockXClient client = new StockXClient() {
            @Override
            public cn.hutool.core.lang.Pair<Integer, List<StockXPriceExcel>> searchItemWithPrice(
                    String query, Integer pageIndex, String sort, String searchType, String country,
                    StockXAccount account) {
                return cn.hutool.core.lang.Pair.of(0, List.of());
            }
        };
        StockXAccount account = new StockXAccount();
        account.setName("account-a");

        new StockXPurchaseGuidanceTaskRunner(account, 17L,
                new StockXPurchaseGuidanceTaskRunner.KeywordQuery(List.of("nothing"), List.of("featured"), 3, "shoes"),
                client, null, taskMapper, mapper(TaskItemMapper.class, (method, args) -> null)).run();

        assertThat(failure.get()).contains("未返回任何商品");
    }

    private static StockXPriceExcel apparel(String variantId, String label) {
        StockXPriceExcel row = new StockXPriceExcel();
        row.setId(variantId);
        row.setModelNo("HOODIE-1");
        row.setSizeLabel(label);
        row.setSizeType("us");
        return row;
    }

    private static ModelNoSearchExcel input(String modelNo, String size) {
        ModelNoSearchExcel row = new ModelNoSearchExcel();
        row.setModelNo(modelNo);
        row.setSize(size);
        return row;
    }

    @SuppressWarnings("unchecked")
    private static <T> T mapper(Class<T> type, Handler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            Object result = handler.call(method.getName(), args);
            if (result != null) return result;
            if (method.getReturnType() == int.class) return 1;
            if (method.getReturnType() == long.class) return 0L;
            if (method.getReturnType() == boolean.class) return false;
            return null;
        });
    }

    private interface Handler {
        Object call(String method, Object[] args);
    }
}
