package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.service.EbayOAuthService;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("ebay")
public class EbayController {

    private final EbayOAuthService oauthService;

    public EbayController(EbayOAuthService oauthService) {
        this.oauthService = oauthService;
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
