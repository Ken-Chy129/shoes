package cn.ken.shoes.controller;

import cn.ken.shoes.annotation.CheckApiToken;
import cn.ken.shoes.client.EbayApiException;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.entity.EbayMemberMessageDO;
import cn.ken.shoes.service.EbayMemberMessageService;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** eBay 买家提问自动回复：状态、记录、订阅推送、手动对账。 */
@Slf4j
@RestController
@RequestMapping("task/ebay/messages")
public class EbayMemberMessageController {

    private final EbayMemberMessageService service;

    public EbayMemberMessageController(EbayMemberMessageService service) {
        this.service = service;
    }

    @GetMapping("status")
    public Result<JSONObject> status() {
        return Result.buildSuccess(service.status());
    }

    /** status 可选：processing/replied/manual/failed/skipped。 */
    @GetMapping("list")
    public Result<List<EbayMemberMessageDO>> list(@RequestParam(required = false) String status,
                                                  @RequestParam(defaultValue = "100") int limit) {
        return Result.buildSuccess(service.recent(status, limit));
    }

    /** 查询 eBay 侧当前的推送地址和已订阅事件（消耗 2 次 Trading 调用）。 */
    @CheckApiToken
    @GetMapping("notificationPreferences")
    public Result<JSONObject> notificationPreferences() {
        try {
            return Result.buildSuccess(service.notificationPreferences());
        } catch (EbayApiException | IllegalArgumentException e) {
            return Result.buildError(e.getMessage());
        }
    }

    /** 订阅 AskSellerQuestion 推送到 ebay.messages.deliveryUrl（只需调用一次）。 */
    @CheckApiToken
    @PostMapping("subscribe")
    public Result<Void> subscribe() {
        try {
            service.subscribe();
            return Result.buildSuccess();
        } catch (EbayApiException | IllegalArgumentException e) {
            return Result.buildError(e.getMessage());
        }
    }

    @CheckApiToken
    @PostMapping("reconcile")
    public Result<Integer> reconcile() {
        if (!service.isEnabled()) {
            return Result.buildError("eBay消息功能未开启(ebay.messages.enabled)");
        }
        int handled = service.reconcile();
        return handled < 0 ? Result.buildError("对账正在进行中") : Result.buildSuccess(handled);
    }
}
