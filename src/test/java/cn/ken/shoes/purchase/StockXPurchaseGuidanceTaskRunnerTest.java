package cn.ken.shoes.purchase;

import cn.ken.shoes.client.StockXClient;
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
