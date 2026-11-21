package cn.ken.shoes.controller;

import cn.ken.shoes.client.EbayApiException;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.model.entity.EbayMemberMessageDO;
import cn.ken.shoes.service.EbayMemberMessageService;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
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

    /** 网页提醒轮询：返回 gmt_create 晚于 after（毫秒时间戳）的消息，只查本地库。 */
    @GetMapping("latest")
    public Result<List<EbayMemberMessageDO>> latest(@RequestParam long after) {
        return Result.buildSuccess(service.latest(after));
    }

    /** 不回显 devId。 */
    @GetMapping("settings")
    public Result<JSONObject> settings() {
        return Result.buildSuccess(service.settingsView());
    }

    @PostMapping("settings")
    public Result<JSONObject> saveSettings(@RequestBody EbayProperties.MessageSettings settings) {
        try {
            service.saveSettings(settings);
            return Result.buildSuccess(service.settingsView());
        } catch (IllegalArgumentException e) {
            return Result.buildError(e.getMessage());
        } catch (IOException e) {
            log.error("保存eBay消息配置失败", e);
            return Result.buildError("保存配置文件失败: " + e.getMessage());
        }
    }

    /** 查询 eBay 侧当前的推送地址和已订阅事件（消耗 2 次 Trading 调用）。 */
    @GetMapping("notificationPreferences")
    public Result<JSONObject> notificationPreferences() {
        try {
            return Result.buildSuccess(service.notificationPreferences());
        } catch (EbayApiException | IllegalArgumentException e) {
            return Result.buildError(e.getMessage());
        }
    }

    /** 订阅 AskSellerQuestion 推送到配置的推送地址（只需调用一次）。 */
    @PostMapping("subscribe")
    public Result<Void> subscribe() {
        try {
            service.subscribe();
            return Result.buildSuccess();
        } catch (EbayApiException | IllegalArgumentException e) {
            return Result.buildError(e.getMessage());
        }
    }

    @PostMapping("reconcile")
    public Result<Integer> reconcile() {
        if (!service.isEnabled()) {
            return Result.buildError("eBay消息功能未开启");
        }
        int handled = service.reconcile();
        return handled < 0 ? Result.buildError("对账正在进行中") : Result.buildSuccess(handled);
    }
}
