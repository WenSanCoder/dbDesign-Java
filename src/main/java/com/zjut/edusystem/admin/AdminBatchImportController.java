package com.zjut.edusystem.admin;

import com.zjut.edusystem.common.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/batch-import")
public class AdminBatchImportController {
    private final AdminBatchImportService service;

    public AdminBatchImportController(AdminBatchImportService service) {
        this.service = service;
    }

    @PostMapping(value = "/{type}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> importXlsx(
            @PathVariable String type,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) Long operatorUserId
    ) {
        return ApiResponse.ok("批量导入完成", service.importXlsx(type, file, operatorUserId));
    }
}
