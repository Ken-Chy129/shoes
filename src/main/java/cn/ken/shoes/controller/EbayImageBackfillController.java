package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.service.EbayImageBackfillService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("task/ebay/imageBackfill")
public class EbayImageBackfillController {

    private final EbayImageBackfillService service;

    public EbayImageBackfillController(EbayImageBackfillService service) {
        this.service = service;
    }

    @PostMapping("start")
    public Result<Boolean> start(@RequestParam(defaultValue = "true") boolean refreshCatalog,
                                 @RequestParam(defaultValue = "false") boolean replaceListing,
                                 @RequestParam(defaultValue = "0") int limit) {
        if (!service.start(refreshCatalog, replaceListing, limit)) {
            return Result.buildError("图片补数据任务正在运行");
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
