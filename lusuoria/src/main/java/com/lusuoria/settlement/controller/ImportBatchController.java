package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.entity.ImportBatch;
import com.lusuoria.settlement.repository.ImportBatchRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

/**
 * "导入历史"页面用：查看某个模块历次 Excel 导入的进度和结果。
 * 目前只有红人合作跟踪一个模块在用异步导入，其他模块数据量小、还是同步导入，
 * 不会往这张表里写记录。
 */
@RestController
@RequestMapping("/api/import-batches")
public class ImportBatchController {

    @Autowired private ImportBatchRepository importBatchRepo;

    @GetMapping
    public ApiResponse<Page<ImportBatch>> list(
            @RequestParam(required = false, defaultValue = "COLLABORATION_TRACKING") String module,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ApiResponse.success(importBatchRepo.findByModuleOrderByCreatedAtDesc(module, pageable));
    }

    @GetMapping("/{id}")
    public ApiResponse<ImportBatch> getById(@PathVariable Long id) {
        return ApiResponse.success(importBatchRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("导入批次不存在：" + id)));
    }
}
