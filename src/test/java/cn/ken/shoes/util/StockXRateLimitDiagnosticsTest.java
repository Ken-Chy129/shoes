package cn.ken.shoes.util;

import cn.ken.shoes.exception.StockXRateLimitException;
import cn.ken.shoes.exception.StockXRateLimitType;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockXRateLimitDiagnosticsTest {

    @Test
    void classifiesBatchLimitBeforeTheGeneric429Marker() {
        String response = "{\"httpStatusCode\":429,\"message\":\"Batch usage limit exceeded\"}";

        assertThat(StockXRateLimitGuard.matchedSignal(429, response)).isEqualTo("BatchUsageLimit");
    }

    @Test
    void recordsAnUnknownUpdateSellerListing429AsHttp429() {
        String response = "{\"errors\":[{\"message\":\"rate limited\"}]}";

        assertThat(StockXRateLimitGuard.matchedSignal(429, response)).isEqualTo("HTTP429");
    }

    @Test
    void keepsOnlyRateLimitHeadersForDiagnostics() {
        Headers headers = Headers.of(
                "Retry-After", "60",
                "X-RateLimit-Remaining", "0",
                "Set-Cookie", "sensitive-cookie",
                "Content-Type", "application/json");

        Map<String, String> diagnosticHeaders = StockXRateLimitGuard.rateLimitHeaders(headers);

        assertThat(diagnosticHeaders)
                .containsEntry("Retry-After", "60")
                .containsEntry("X-RateLimit-Remaining", "0")
                .doesNotContainKeys("Set-Cookie", "Content-Type");
    }

    @Test
    void redactsTokensFromLoggedResponseBodies() {
        String response = "{\"authorization\":\"Bearer secret.jwt.value\","
                + "\"access_token\":\"access-secret\",\"message\":\"Too Many Requests\"}";

        String sanitized = StockXRateLimitGuard.sanitizeForLog(response, 500);

        assertThat(sanitized)
                .contains("Too Many Requests")
                .doesNotContain("secret.jwt.value", "access-secret");
    }

    @Test
    void priceUpdateHandsTheFirstRealBatchLimitToTheCoordinator() {
        assertThatThrownBy(() -> StockXRateLimitGuard.execute(
                () -> "{\"httpStatusCode\":429,\"message\":\"Batch usage limit exceeded\"}",
                "account-a", "BulkUpdateListings", null))
                .isInstanceOfSatisfying(StockXRateLimitException.class, error -> {
                    assertThat(error.getType()).isEqualTo(StockXRateLimitType.BATCH);
                    assertThat(error.getSignal()).isEqualTo("BatchUsageLimit");
                    assertThat(error.getCooldownMs()).isZero();
                });
    }

    @Test
    void unknownSingle429IsClassifiedAsGeneralUntilLogsShowOtherwise() {
        assertThatThrownBy(() -> StockXRateLimitGuard.execute(
                () -> "{\"httpStatusCode\":429,\"message\":\"rate limited\"}",
                "account-a", "UpdateSellerListing", null))
                .isInstanceOfSatisfying(StockXRateLimitException.class, error -> {
                    assertThat(error.getType()).isEqualTo(StockXRateLimitType.GENERAL);
                    assertThat(error.getSignal()).isEqualTo("GraphQL429");
                });
    }
}
