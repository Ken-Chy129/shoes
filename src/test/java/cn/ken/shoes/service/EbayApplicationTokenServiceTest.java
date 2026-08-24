package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayOAuthTokenClient;
import cn.ken.shoes.config.EbayProperties;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EbayApplicationTokenServiceTest {

    @Test
    void cachesTheApplicationTokenUntilTheRefreshBuffer() {
        EbayProperties properties = new EbayProperties();
        properties.setApplicationScope("https://api.ebay.com/oauth/api_scope");
        EbayOAuthTokenClient client = mock(EbayOAuthTokenClient.class);
        when(client.requestApplicationToken(properties.getApplicationScope()))
                .thenReturn(new JSONObject(true)
                        .fluentPut("access_token", "first-token")
                        .fluentPut("expires_in", 3600L),
                        new JSONObject(true)
                                .fluentPut("access_token", "second-token")
                                .fluentPut("expires_in", 3600L));
        AtomicLong now = new AtomicLong(1_000_000L);
        EbayApplicationTokenService service = new EbayApplicationTokenService(
                properties, client, now::get);

        assertThat(service.getValidAccessToken()).isEqualTo("first-token");
        now.addAndGet(3_300_001L);
        assertThat(service.getValidAccessToken()).isEqualTo("second-token");
        verify(client, times(2)).requestApplicationToken(properties.getApplicationScope());
    }
}
