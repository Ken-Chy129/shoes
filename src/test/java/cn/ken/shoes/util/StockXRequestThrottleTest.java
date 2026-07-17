package cn.ken.shoes.util;

import com.google.common.util.concurrent.RateLimiter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StockXRequestThrottleTest {

    @Test
    void graphqlAndRestShareTheSameOneQpsLimiterPerAccount() {
        RateLimiter graphql = LimiterHelper.stockxAccountLimiter("account-a");
        RateLimiter rest = LimiterHelper.stockxAccountLimiter("account-a");

        assertThat(graphql).isSameAs(rest);
        assertThat(graphql.getRate()).isEqualTo(1.0);
    }

    @Test
    void transportDoesNotImmediatelyRetryRateLimitResponses() {
        assertThat(HttpUtil.shouldRetryHttpStatus(429)).isFalse();
        assertThat(HttpUtil.shouldRetryHttpStatus(403)).isFalse();
    }

    @Test
    void http429IsPreservedEvenWhenStockxReturnsAnUnknownBodyShape() {
        String marked = HttpUtil.attachHttpStatusMarker(
                "{\"errors\":[{\"message\":\"temporarily unavailable\"}]}", 429);

        assertThat(StockXRateLimitGuard.isRateLimited(marked)).isTrue();
        assertThat(StockXRateLimitGuard.matchedSignal(marked)).isEqualTo("HTTP429");
    }
}
