package cn.ken.shoes.controller;

import cn.ken.shoes.common.StockXSortEnum;
import cn.ken.shoes.config.CommonConfig;
import cn.ken.shoes.service.FileService;
import cn.ken.shoes.service.StockXService;
import jakarta.annotation.Resource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequestMapping("file")
public class FileController {

    @Resource
    private FileService fileService;

    @Resource
    private StockXService stockXService;

    @GetMapping("sizeChart")
    public ResponseEntity<InputStreamResource> downloadSizeChart() throws IOException {
        FileSystemResource file = new FileSystemResource(CommonConfig.DOWNLOAD_PATH + CommonConfig.SIZE_CHART_NAME);
        if (!file.exists()) {
            fileService.doWriteSizeCharExcel();
            file = new FileSystemResource(CommonConfig.DOWNLOAD_PATH + CommonConfig.SIZE_CHART_NAME);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.add("Cache-Control", "no-cache, no-store, must-revalidate");
        headers.add("Content-Disposition", "attachment; filename=" + URLEncoder.encode(CommonConfig.SIZE_CHART_NAME, StandardCharsets.UTF_8));
        headers.add("Pragma", "no-cache");
        headers.add("Expires", "0");

        return ResponseEntity
                .ok()
                .headers(headers)
                .contentLength(file.contentLength())
                .contentType(MediaType.parseMediaType("application/octet-stream"))
                .body(new InputStreamResource(file.getInputStream()));
    }

    @GetMapping("downloadItemsForSearch")
    public ResponseEntity<InputStreamResource> downloadItemsForSearch(String query, String sortType) throws IOException {
        if (sortType == null) {
            sortType = StockXSortEnum.FEATURED.getCode();
        }
        stockXService.searchItems(query, sortType, 20);
        FileSystemResource file = new FileSystemResource(STR."file/\{query}.xlsx");
        if (!file.exists()) {
            return ResponseEntity.noContent().build();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.add("Cache-Control", "no-cache, no-store, must-revalidate");
        headers.add("Content-Disposition", "attachment; filename=" + URLEncoder.encode(STR."file/\{query}.xlsx", StandardCharsets.UTF_8));
        headers.add("Pragma", "no-cache");
        headers.add("Expires", "0");

        return ResponseEntity
                .ok()
                .headers(headers)
                .contentLength(file.contentLength())
                .contentType(MediaType.parseMediaType("application/octet-stream"))
                .body(new InputStreamResource(file.getInputStream()));
    }

    @PostMapping("/upload")
    public ResponseEntity<String> uploadFile(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("文件为空，请选择文件后上传！");
        }

        try {
            // 获取文件名
            String fileName = file.getOriginalFilename();

            // 构建文件保存路径
            Path filePath = Paths.get(CommonConfig.UPLOAD_PATH + fileName);

            // 将文件保存到指定路径
            Files.copy(file.getInputStream(), filePath);

            return ResponseEntity.ok("文件上传成功！文件路径：" + filePath.toString());
        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("文件上传失败：" + e.getMessage());
        }
    }
}
