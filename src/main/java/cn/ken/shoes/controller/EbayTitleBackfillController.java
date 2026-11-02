package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.service.EbayTitleBackfillService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("task/ebay/titleBackfill")
public class EbayTitleBackfillController {

    private final EbayTitleBackfillService service;

    public EbayTitleBackfillController(EbayTitleBackfillService service) {
        this.service = service;
    }

    @PostMapping("start")
    public Result<Boolean> start(@RequestParam(defaultValue = "true") boolean dryRun,
                                 @RequestParam(defaultValue = "0") int limit,
                                 @RequestParam(required = false) List<String> styleIds,
                                 @RequestParam(defaultValue = "1") int concurrency) {
        if (!service.start(dryRun, limit, styleIds, concurrency)) {
            return Result.buildError("标题补数据任务正在运行");
        }
        return Result.buildSuccess(true);
    }

    @PostMapping("cancel")
    public Result<Boolean> cancel() {
        service.cancel();
        return Result.buildSuccess(true);
    }

    @GetMapping("status")
    public Result<Map<String, Object>> status() {
        return Result.buildSuccess(service.status());
    }
}
