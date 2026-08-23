package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.service.EbayOAuthService;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EbayControllerTest {

    @Test
    void exposesAuthorizationRequestWithoutCredentials() {
        EbayOAuthService service = mock(EbayOAuthService.class);
        JSONObject authorization = new JSONObject();
        authorization.put("authorizeUrl", "https://auth.sandbox.ebay.com/oauth2/authorize?state=abc");
        authorization.put("stateExpiresAt", 123L);
        when(service.createAuthorizationRequest()).thenReturn(authorization);
        EbayController controller = new EbayController(service);

        Result<JSONObject> result = controller.authorizationRequest();

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo(authorization);
        assertThat(result.getData().toJSONString()).doesNotContain("client_secret", "access_token", "refresh_token");
    }

    @Test
    void returnsGenericCallbackErrorWithoutLeakingProviderDetails() {
        EbayOAuthService service = mock(EbayOAuthService.class);
        when(service.exchangeAuthorizationCode("code", "state"))
                .thenThrow(new IllegalStateException("provider response contained sensitive detail"));
        EbayController controller = new EbayController(service);

        Result<JSONObject> result = controller.oauthCallback("code", "state");

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("eBay 授权失败，请重新发起授权");
    }

    @Test
    void servesPublicPrivacyPolicyAsHtmlWithoutPersonalContactData() {
        EbayController controller = new EbayController(mock(EbayOAuthService.class));

        ResponseEntity<String> response = controller.privacyPolicy();

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_HTML);
        assertThat(response.getBody())
                .contains("Shoes Inventory Sync", "OAuth", "not sold")
                .doesNotContain("mailto:", "tel:");
    }
}
