package cn.ken.shoes.client;

import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.util.HttpUtil;
import com.alibaba.fastjson.JSON;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class StockXClientSharedReadOperationsTest {

    @Test
    void priceAndBrandReadsRotateAcrossHongKongAccounts() {
        List<StockXAccount> original = new ArrayList<>(StockXConfig.getAccounts());
        try {
            StockXAccount hkA = account("hk-a");
            StockXAccount hkB = account("hk-b");
            StockXConfig.setAccounts(List.of(hkA, hkB));
            StockXClient client = new StockXClient();
            List<String> authorizations = new ArrayList<>();

            try (MockedStatic<HttpUtil> http = Mockito.mockStatic(HttpUtil.class)) {
                http.when(HttpUtil::getStockXDeviceId).thenReturn("test-device");
                http.when(() -> HttpUtil.doPost(
                                eq(StockXConfig.GRAPHQL), anyString(), any(Headers.class)))
                        .thenAnswer(invocation -> {
                            String body = invocation.getArgument(1);
                            Headers headers = invocation.getArgument(2);
                            authorizations.add(headers.get("authorization"));
                            String operation = JSON.parseObject(body).getString("operationName");
                            if ("ProductVariants".equals(operation)) {
                                return "{\"data\":{\"product\":{\"styleId\":\"STYLE-1\",\"variants\":[]}}}";
                            }
                            return "{\"data\":{\"browse\":{\"filtersConfig\":{\"quick\":[{\"name\":\"BRANDS\",\"options\":[]}]}}}}";
                        });

                client.queryPrice("product-1");
                client.queryBrands();
            }

            assertThat(authorizations).containsExactly("Bearer hk-a", "Bearer hk-b");
        } finally {
            StockXConfig.setAccounts(original);
        }
    }

    private static StockXAccount account(String name) {
        StockXAccount account = new StockXAccount();
        account.setName(name);
        account.setCountry("HK");
        account.setAuthorization("Bearer " + name);
        account.setEnabled(true);
        return account;
    }
}
