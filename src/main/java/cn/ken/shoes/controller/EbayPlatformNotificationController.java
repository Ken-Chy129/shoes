package cn.ken.shoes.controller;

import cn.ken.shoes.service.EbayMemberMessageService;
import cn.ken.shoes.service.EbayPlatformNotificationParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trading API Platform Notifications（SOAP）接收端点，目前只处理 AskSellerQuestion。
 *
 * <p>验签通过即返回 200，实际回复在后台线程执行：eBay 不重发失败的推送，连续失败过多还会停推，
 * 所以只有验签失败、报文非法才返回非 200。
 */
@Slf4j
@RestController
@RequestMapping("ebay/notifications/trading")
public class EbayPlatformNotificationController {

    static final int MAX_BODY_BYTES = 256 * 1024;

    private final EbayPlatformNotificationParser parser;
    private final EbayMemberMessageService messageService;

    public EbayPlatformNotificationController(EbayPlatformNotificationParser parser,
                                              EbayMemberMessageService messageService) {
        this.parser = parser;
        this.messageService = messageService;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody(required = false) byte[] payload) {
        if (payload == null || payload.length == 0) {
            return ResponseEntity.badRequest().build();
        }
        if (payload.length > MAX_BODY_BYTES) {
            return ResponseEntity.status(413).build();
        }
        EbayPlatformNotificationParser.Notification notification;
        try {
            notification = parser.parseAndVerify(payload);
        } catch (SecurityException e) {
            log.warn("拒绝eBay平台推送: {}", e.getMessage());
            return ResponseEntity.status(412).build();
        } catch (IllegalArgumentException e) {
            log.warn("eBay平台推送格式异常: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
        try {
            messageService.onNotification(notification);
        } catch (RuntimeException e) {
            // 已验签的推送一律回 200，漏处理的由对账补上
            log.error("处理eBay平台推送异常, event:{}", notification.eventName(), e);
        }
        return ResponseEntity.ok().build();
    }
}
