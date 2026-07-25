package cn.ken.shoes.client;

import cn.ken.shoes.model.entity.PoisonPriceDO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PoisonClientPartnerBatchPriceTest {

    @Test
    void parsesPartnerBatchPricesAndFiltersZeroPrices() {
        String updateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String response = """
                {
                  "code": 200,
                  "sku_list": [
                    {
                      "article_number": "FW2499",
                      "update_time": "%s",
                      "data": [
                        {"size": "36", "minprice": 152000},
                        {"size": "36.5", "minprice": 145900},
                        {"size": "37", "minprice": 0}
                      ]
                    }
                  ]
                }
                """.formatted(updateTime);

        Optional<List<PoisonPriceDO>> parsed = PoisonClient.parsePartnerBatchPriceResponse(response);

        assertThat(parsed).isPresent();
        assertThat(parsed.orElseThrow())
                .extracting(PoisonPriceDO::getModelNo, PoisonPriceDO::getEuSize, PoisonPriceDO::getPrice)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("FW2499", "36", 1520),
                        org.assertj.core.groups.Tuple.tuple("FW2499", "36.5", 1459)
                );
    }

    @Test
    void treatsPartnerApiErrorsAsUnavailableForFallback() {
        Optional<List<PoisonPriceDO>> parsed = PoisonClient.parsePartnerBatchPriceResponse(
                "{\"code\":401,\"message\":\"unauthorized\"}"
        );

        assertThat(parsed).isEmpty();
    }

    @Test
    void treatsSuccessfulEmptyResponseAsConfirmedNoPrice() {
        Optional<List<PoisonPriceDO>> parsed = PoisonClient.parsePartnerBatchPriceResponse(
                "{\"code\":200,\"sku_list\":[]}"
        );

        assertThat(parsed).isPresent();
        assertThat(parsed.orElseThrow()).isEmpty();
    }
}
