package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayOAuthTokenClient;
import cn.ken.shoes.config.EbayProperties;
import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.function.LongSupplier;

@Service
public class EbayApplicationTokenService {

    private static final long REFRESH_BUFFER_MS = 5 * 60 * 1000L;

    private final EbayProperties properties;
    private final EbayOAuthTokenClient tokenClient;
    private final LongSupplier clock;
    private String accessToken;
    private long expiresAt;

    @Autowired
    public EbayApplicationTokenService(EbayProperties properties,
                                       EbayOAuthTokenClient tokenClient) {
        this(properties, tokenClient, System::currentTimeMillis);
    }

    EbayApplicationTokenService(EbayProperties properties,
                                EbayOAuthTokenClient tokenClient,
                                LongSupplier clock) {
        this.properties = properties;
        this.tokenClient = tokenClient;
        this.clock = clock;
    }

    public synchronized String getValidAccessToken() {
        long now = clock.getAsLong();
        if (accessToken != null && !accessToken.isBlank()
                && expiresAt - REFRESH_BUFFER_MS > now) {
            return accessToken;
        }
        JSONObject response = tokenClient.requestApplicationToken(properties.getApplicationScope());
        String newToken = response.getString("access_token");
        long expiresIn = response.getLongValue("expires_in");
        if (newToken == null || newToken.isBlank() || expiresIn <= 0) {
            throw new IllegalStateException("eBay application token response is incomplete");
        }
        accessToken = newToken;
        expiresAt = now + expiresIn * 1000L;
        return accessToken;
    }
}
