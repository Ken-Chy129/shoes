package cn.ken.shoes.client;

import cn.ken.shoes.model.entity.PoisonPriceDO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class PoisonClientPopPriceTest {

    @Test
    void joinsPopBidPricesToArticleAndNormalizedSizeByDwSkuId() {
        String spuResponse = """
                {
                  "code": 200,
                  "msg": "Successful",
                  "data": {
                    "total": 1,
                    "spuList": [{
                      "dwSpuId": 63901795,
                      "dwDesignerId": "FW2499",
                      "skuList": [
                        {"dwSkuId": 1168367681, "saleAttr": [{"enName": "Size", "enValue": "36⅔"}]},
                        {"dwSkuId": 1168367682, "saleAttr": [{"enName": "Size", "enValue": "36.5"}]},
                        {"dwSkuId": 1168367683, "saleAttr": [{"enName": "Size", "enValue": "41⅓"}]}
                      ]
                    }]
                  }
                }
                """;
        String bidResponse = """
                {
                  "code": 200,
                  "msg": "Successful",
                  "data": {
                    "total": 4,
                    "items": [
                      {"dwSkuId": 1168367681, "minBidPrice": 145700, "fulfillmentMode": "NORMAL"},
                      {"dwSkuId": 1168367682, "minBidPrice": 140000, "fulfillmentMode": "NORMAL"},
                      {"dwSkuId": 1168367683},
                      {"dwSkuId": 9999999999, "minBidPrice": 100000}
                    ]
                  }
                }
                """;

        Optional<PoisonClient.PopSpuLookup> lookup = PoisonClient.parsePopSpuLookup(spuResponse);
        Optional<List<PoisonPriceDO>> prices = lookup.flatMap(value ->
                PoisonClient.parsePopBidPrices(bidResponse, value));

        assertThat(lookup).isPresent();
        assertThat(lookup.orElseThrow().spuIds()).containsExactly(63901795L);
        assertThat(prices).isPresent();
        assertThat(prices.orElseThrow())
                .extracting(PoisonPriceDO::getModelNo, PoisonPriceDO::getEuSize, PoisonPriceDO::getPrice)
                .containsExactly(tuple("FW2499", "36.5", 1400));
    }

    @Test
    void treatsSuccessfulBidResponseWithoutPricesAsConfirmedNoPrice() {
        String spuResponse = """
                {"code":200,"data":{"spuList":[{
                  "dwSpuId":63901795,
                  "dwDesignerId":"IF4396-104",
                  "skuList":[{"dwSkuId":1168367681,"saleAttr":[{"enName":"Size","enValue":"40"}]}]
                }]}}
                """;
        String bidResponse = """
                {"code":200,"data":{"total":1,"items":[{"dwSkuId":1168367681,"supplementPrice":[]}]}}
                """;

        PoisonClient.PopSpuLookup lookup = PoisonClient.parsePopSpuLookup(spuResponse).orElseThrow();
        Optional<List<PoisonPriceDO>> prices = PoisonClient.parsePopBidPrices(bidResponse, lookup);

        assertThat(prices).isPresent();
        assertThat(prices.orElseThrow()).isEmpty();
    }

    @Test
    void treatsSuccessfulSpuResponseWithoutMatchesAsConfirmedNoPrice() {
        Optional<PoisonClient.PopSpuLookup> lookup = PoisonClient.parsePopSpuLookup(
                "{\"code\":200,\"data\":{\"total\":0,\"spuList\":[]}}");

        assertThat(lookup).isPresent();
        assertThat(lookup.orElseThrow().spuIds()).isEmpty();
        assertThat(lookup.orElseThrow().skuMetadataById()).isEmpty();
    }

    @Test
    void treatsFailedPopResponsesAsUnavailableForFallback() {
        assertThat(PoisonClient.parsePopSpuLookup("{\"code\":401,\"msg\":\"unauthorized\"}"))
                .isEmpty();
        assertThat(PoisonClient.parsePopSpuLookup("{\"code\":200,\"data\":{}}"))
                .isEmpty();
        assertThat(PoisonClient.parsePopSpuLookup(
                "{\"code\":200,\"data\":{\"total\":2,\"spuList\":[{\"dwSpuId\":1," +
                        "\"dwDesignerId\":\"FW2499\",\"skuList\":[]}]}}"))
                .isEmpty();
        assertThat(PoisonClient.parsePopSpuLookup(
                "{\"code\":200,\"data\":{\"spuList\":[{\"dwSpuId\":1," +
                        "\"dwDesignerId\":\"FW2499\"}]}}"))
                .isEmpty();

        PoisonClient.PopSpuLookup emptyLookup = new PoisonClient.PopSpuLookup(List.of(), java.util.Map.of());
        assertThat(PoisonClient.parsePopBidPrices(
                "{\"code\":500,\"msg\":\"system error\"}", emptyLookup))
                .isEmpty();
        assertThat(PoisonClient.parsePopBidPrices("{\"code\":200,\"data\":{}}", emptyLookup))
                .isEmpty();
    }
}
