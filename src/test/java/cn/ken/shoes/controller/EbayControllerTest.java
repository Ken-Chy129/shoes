package cn.ken.shoes.controller;

import cn.ken.shoes.annotation.CheckApiToken;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.ebay.EbayInventoryLocationRequest;
import cn.ken.shoes.model.ebay.EbayListingRequest;
import cn.ken.shoes.model.ebay.EbayListingResult;
import cn.ken.shoes.service.EbayListingService;
import cn.ken.shoes.service.EbayOAuthService;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EbayControllerTest {

    @Test
    void exposesAuthorizationRequestWithoutCredentials() {
        EbayOAuthService service = mock(EbayOAuthService.class);
        JSONObject authorization = new JSONObject();
        authorization.put("authorizeUrl", "https://auth.sandbox.ebay.com/oauth2/authorize?state=abc");
        authorization.put("stateExpiresAt", 123L);
        when(service.createAuthorizationRequest()).thenReturn(authorization);
        EbayController controller = new EbayController(service, mock(EbayListingService.class));

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
        EbayController controller = new EbayController(service, mock(EbayListingService.class));

        Result<JSONObject> result = controller.oauthCallback("code", "state");

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("eBay 授权失败，请重新发起授权");
    }

    @Test
    void servesPublicPrivacyPolicyAsHtmlWithoutPersonalContactData() {
        EbayController controller = new EbayController(
                mock(EbayOAuthService.class), mock(EbayListingService.class));

        ResponseEntity<String> response = controller.privacyPolicy();

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_HTML);
        assertThat(response.getBody())
                .contains("Shoes Inventory Sync", "OAuth", "not sold")
                .doesNotContain("mailto:", "tel:");
    }

    @Test
    void publishesListingThroughProtectedEndpoint() throws Exception {
        EbayListingService listingService = mock(EbayListingService.class);
        EbayListingRequest request = new EbayListingRequest();
        EbayListingResult published = new EbayListingResult(
                "sku-1", "offer-1", "listing-1", "sandbox");
        when(listingService.publish(request)).thenReturn(published);
        EbayController controller = new EbayController(mock(EbayOAuthService.class), listingService);

        Result<EbayListingResult> result = controller.publishListing(request);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo(published);
        assertThat(EbayController.class.getMethod("publishListing", EbayListingRequest.class)
                .isAnnotationPresent(CheckApiToken.class)).isTrue();
    }

    @Test
    void returnsGenericListingErrorWithoutProviderDetails() {
        EbayListingService listingService = mock(EbayListingService.class);
        EbayListingRequest request = new EbayListingRequest();
        when(listingService.publish(request))
                .thenThrow(new IllegalStateException("provider response access-token and seller data"));
        EbayController controller = new EbayController(mock(EbayOAuthService.class), listingService);

        Result<EbayListingResult> result = controller.publishListing(request);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("eBay 上架失败，请检查地点、业务政策和商品资料");
        assertThat(result.getErrorMsg()).doesNotContain("access-token", "seller data");
    }

    @Test
    void exposesProtectedPrerequisitesAndLocationSetup() throws Exception {
        EbayListingService listingService = mock(EbayListingService.class);
        JSONObject prerequisites = new JSONObject().fluentPut("environment", "sandbox");
        when(listingService.getPrerequisites("EBAY_US")).thenReturn(prerequisites);
        EbayController controller = new EbayController(mock(EbayOAuthService.class), listingService);

        Result<JSONObject> result = controller.listingPrerequisites("EBAY_US");
        EbayInventoryLocationRequest location = new EbayInventoryLocationRequest();
        Result<Void> locationResult = controller.createInventoryLocation(location);

        assertThat(result.getData()).isEqualTo(prerequisites);
        assertThat(locationResult.getSuccess()).isTrue();
        verify(listingService).createInventoryLocation(location);
        assertThat(EbayController.class.getMethod("listingPrerequisites", String.class)
                .isAnnotationPresent(CheckApiToken.class)).isTrue();
        assertThat(EbayController.class.getMethod(
                "createInventoryLocation", EbayInventoryLocationRequest.class)
                .isAnnotationPresent(CheckApiToken.class)).isTrue();
    }
}
