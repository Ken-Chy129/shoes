package cn.ken.shoes.manager;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.common.PoisonPriceMode;
import cn.ken.shoes.model.entity.PoisonPriceDO;
import cn.ken.shoes.model.entity.SpecialPriceDO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PriceManagerSizeNormalizationTest {

    @Test
    void refreshPricesBypassesExistingCacheEntries() throws Exception {
        PriceManager manager = new PriceManager();
        manager.putModelNoPrice("STYLE-A", List.of(poisonPrice("STYLE-A", "42", 100)));
        PoisonClient client = new PoisonClient() {
            @Override
            public List<PoisonPriceDO> batchQueryPrice(List<String> modelNos) {
                PoisonPriceDO fresh = poisonPrice("STYLE-A", "42", 200);
                fresh.setUpdateTime(new Date());
                return List.of(fresh);
            }
        };
        setField(manager, "poisonClient", client);

        manager.refreshPrices(Set.of("STYLE-A"));

        assertThat(manager.getPoisonPrice("STYLE-A", "42")).isEqualTo(200);
    }

    @Test
    void matchesStockXAsciiFractionSizesAgainstNormalizedPoisonSizes() {
        PriceManager priceManager = new PriceManager();
        priceManager.putModelNoPrice("STYLE-1", List.of(
                poisonPrice("STYLE-1", "45", 1001),
                poisonPrice("STYLE-1", "38.5", 1002)
        ));

        assertThat(priceManager.getPoisonPrice("STYLE-1", "45 1/3")).isEqualTo(1001);
        assertThat(priceManager.getPoisonPrice("STYLE-1", "38 2/3")).isEqualTo(1002);
    }

    @Test
    void matchesStockXUnicodeFractionSizesAgainstNormalizedPoisonSizes() {
        PriceManager priceManager = new PriceManager();
        priceManager.putModelNoPrice("STYLE-2", List.of(
                poisonPrice("STYLE-2", "47", 2001),
                poisonPrice("STYLE-2", "41.5", 2002)
        ));

        assertThat(priceManager.getPoisonPrice("STYLE-2", "47⅓")).isEqualTo(2001);
        assertThat(priceManager.getPoisonPrice("STYLE-2", "41⅔")).isEqualTo(2002);
    }

    @Test
    void keepsExistingRawFractionSpecialPriceOverridesWorking() {
        SpecialPriceDO specialPrice = new SpecialPriceDO();
        specialPrice.setModelNo("STYLE-SPECIAL");
        specialPrice.setEuSize("45 1/3");
        specialPrice.setPrice(3001);
        ShoesContext.addSpecialPrice(specialPrice);
        try {
            assertThat(new PriceManager().getPoisonPrice("STYLE-SPECIAL", "45 1/3"))
                    .isEqualTo(3001);
        } finally {
            ShoesContext.clearSpecialPrice();
        }
    }

    @Test
    void supportsExplicitNormalAndThreeFivePriceModes() {
        PriceManager priceManager = new PriceManager();
        priceManager.putModelNoPrice("STYLE-MODE", List.of(
                poisonPrice("STYLE-MODE", "42", 1000)
        ));

        assertThat(priceManager.getPoisonPrice("STYLE-MODE", "42", PoisonPriceMode.NORMAL))
                .isEqualTo(1000);
        assertThat(priceManager.getPoisonPrice("STYLE-MODE", "42", PoisonPriceMode.THREE_FIVE))
                .isEqualTo(902);
    }

    private static PoisonPriceDO poisonPrice(String modelNo, String euSize, int price) {
        PoisonPriceDO item = new PoisonPriceDO();
        item.setModelNo(modelNo);
        item.setEuSize(euSize);
        item.setPrice(price);
        return item;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
