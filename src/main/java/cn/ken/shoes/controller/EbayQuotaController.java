package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.service.EbayTradingQuotaService;
import com.alibaba.fastjson.JSONObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** eBay Trading API 日额度：官方已用/剩余次数 + 本进程按接口的调用统计与熔断状态（只读）。 */
@RestController
@RequestMapping("task/ebay")
public class EbayQuotaController {

    private final EbayTradingQuotaService quotaService;

    public EbayQuotaController(EbayTradingQuotaService quotaService) {
        this.quotaService = quotaService;
    }

    @GetMapping("tradingQuota")
    public Result<JSONObject> tradingQuota() {
        return Result.buildSuccess(quotaService.status());
    }
}
