package cn.ken.shoes.controller;

import cn.ken.shoes.config.CommonConfig;
import cn.ken.shoes.mapper.SearchTaskMapper;
import cn.ken.shoes.model.entity.SearchTaskDO;
import cn.ken.shoes.service.FileService;
import jakarta.annotation.Resource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("file")
public class FileController {

    @Resource
    private FileService fileService;

    @Resource
    private SearchTaskMapper searchTaskMapper;

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

    @GetMapping("downloadSearchResult")
    public ResponseEntity<InputStreamResource> downloadSearchResult(Long searchTaskId) throws IOException {
        // 根据searchTaskId找到文件路径进行下载
        SearchTaskDO searchTask = searchTaskMapper.selectById(searchTaskId);
        if (searchTask == null) {
            return ResponseEntity.notFound().build();
        }

        String filePath = searchTask.getFilePath();
        if (filePath == null || filePath.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        FileSystemResource file = new FileSystemResource(filePath);
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }

        // 从文件路径中提取文件名
        String fileName = new File(filePath).getName();

        HttpHeaders headers = new HttpHeaders();
        headers.add("Cache-Control", "no-cache, no-store, must-revalidate");
        headers.add("Content-Disposition", "attachment; filename=" + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
        headers.add("Pragma", "no-cache");
        headers.add("Expires", "0");

        return ResponseEntity
                .ok()
                .headers(headers)
                .contentLength(file.contentLength())
                .contentType(MediaType.parseMediaType("application/octet-stream"))
                .body(new InputStreamResource(file.getInputStream()));
    }

}
