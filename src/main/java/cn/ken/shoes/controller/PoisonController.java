package cn.ken.shoes.controller;

import cn.ken.shoes.service.FileService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("poison")
public class PoisonController {

    @Resource
    private FileService fileService;

    @GetMapping("test")
    public void test() throws InterruptedException {
        fileService.queryModelNoPriceByExcel("1");
    }

    @GetMapping("excel")
    public void testExcel() {
        fileService.doWritePoisonPriceExcel();
    }
}
