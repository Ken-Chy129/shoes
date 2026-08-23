package cn.ken.shoes.controller;

import cn.ken.shoes.annotation.CheckApiToken;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.ebay.EbayInventoryLocationRequest;
import cn.ken.shoes.model.ebay.EbayListingRequest;
import cn.ken.shoes.model.ebay.EbayListingResult;
import cn.ken.shoes.service.EbayListingService;
import cn.ken.shoes.service.EbayOAuthService;
import com.alibaba.fastjson.JSONObject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

@Slf4j
@Validated
@RestController
@RequestMapping("ebay")
public class EbayController {

    private final EbayOAuthService oauthService;
    private final EbayListingService listingService;

    public EbayController(EbayOAuthService oauthService, EbayListingService listingService) {
        this.oauthService = oauthService;
        this.listingService = listingService;
    }

    @GetMapping("oauth/authorize-url")
    public Result<JSONObject> authorizationRequest() {
        try {
            return Result.buildSuccess(oauthService.createAuthorizationRequest());
        } catch (Exception e) {
            log.warn("Unable to create eBay authorization request, type:{}", e.getClass().getSimpleName());
            return Result.buildError("eBay OAuth 尚未配置完成");
        }
    }

    @GetMapping("oauth/status")
    public Result<JSONObject> oauthStatus() {
        return Result.buildSuccess(oauthService.getStatus());
    }

    @GetMapping("oauth/callback")
    public Result<JSONObject> oauthCallback(@RequestParam("code") String code,
                                            @RequestParam("state") String state) {
        try {
            return Result.buildSuccess(oauthService.exchangeAuthorizationCode(code, state));
        } catch (Exception e) {
            log.warn("eBay OAuth callback failed, type:{}", e.getClass().getSimpleName());
            return Result.buildError("eBay 授权失败，请重新发起授权");
        }
    }

    @GetMapping("oauth/declined")
    public Result<Void> oauthDeclined() {
        return Result.buildError("你已取消 eBay 授权，未保存任何令牌");
    }

    @CheckApiToken
    @GetMapping("listing-prerequisites")
    public Result<JSONObject> listingPrerequisites(
            @RequestParam(defaultValue = "EBAY_US")
            @Pattern(regexp = "EBAY_[A-Z0-9_]+") String marketplaceId) {
        try {
            return Result.buildSuccess(listingService.getPrerequisites(marketplaceId));
        } catch (Exception e) {
            log.warn("Unable to query eBay listing prerequisites, type:{}", e.getClass().getSimpleName());
            return Result.buildError("eBay 上架前置资源查询失败，请确认卖家授权有效");
        }
    }

    @CheckApiToken
    @PostMapping("locations")
    public Result<Void> createInventoryLocation(
            @Valid @RequestBody EbayInventoryLocationRequest request) {
        try {
            listingService.createInventoryLocation(request);
            return Result.buildSuccess();
        } catch (Exception e) {
            log.warn("Unable to create eBay inventory location, type:{}", e.getClass().getSimpleName());
            return Result.buildError("eBay 库存地点创建失败，请检查地址或地点标识");
        }
    }

    @CheckApiToken
    @PostMapping("listings")
    public Result<EbayListingResult> publishListing(@Valid @RequestBody EbayListingRequest request) {
        try {
            return Result.buildSuccess(listingService.publish(request));
        } catch (Exception e) {
            log.warn("Unable to publish eBay listing, type:{}", e.getClass().getSimpleName());
            return Result.buildError("eBay 上架失败，请检查地点、业务政策和商品资料");
        }
    }

    @GetMapping(value = "privacy", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> privacyPolicy() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body("""
                        <!doctype html>
                        <html lang="en">
                        <head><meta charset="utf-8"><title>Shoes Inventory Sync Privacy Policy</title></head>
                        <body>
                          <h1>Shoes Inventory Sync Privacy Policy</h1>
                          <p>Last updated: August 23, 2026.</p>
                          <p>This application uses eBay OAuth tokens and seller-authorized inventory and
                          account policy data only to manage eBay listings at the seller's direction.</p>
                          <p>Credentials and seller data are stored on the application's private server.
                          They are not sold or shared with third parties except as required to process
                          requests through eBay.</p>
                          <p>Authorization can be revoked from the seller's eBay account. Stored credentials
                          are retained only while the integration is in use. Privacy questions can be sent
                          to the primary contact registered for this application in the eBay Developers Program.</p>
                        </body>
                        </html>
                        """);
    }
}
