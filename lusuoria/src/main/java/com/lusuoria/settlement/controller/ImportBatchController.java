package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.entity.ImportBatch;
import com.lusuoria.settlement.repository.ImportBatchRepository;
import com.lusuoria.settlement.util.EmployeeRoleUtil;
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
    @Autowired private EmployeeRoleUtil employeeRoleUtil;

    @GetMapping
    public ApiResponse<Page<ImportBatch>> list(
            @RequestParam(required = false, defaultValue = "COLLABORATION_TRACKING") String module,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ApiResponse.success(importBatchRepo.findByModuleAndIsDeletedFalseOrderByCreatedAtDesc(module, pageable));
    }

    @GetMapping("/{id}")
    public ApiResponse<ImportBatch> getById(@PathVariable Long id) {
        return ApiResponse.success(importBatchRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("导入批次不存在：" + id)));
    }

    /**
     * 删除一条导入历史记录本身（供管理层清理误操作/测试产生的脏历史记录用）。
     * 只删这条历史记录，不影响这次导入实际创建/更新过的红人合作跟踪数据——
     * ImportBatch 跟 CollaborationTracking 之间没有关联，删了也追溯不到具体是哪些行。
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        if (!"管理层".equals(employeeRoleUtil.getCurrentEmployeeRole())) {
            return ApiResponse.error(403, "无权限删除导入历史记录");
        }
        ImportBatch batch = importBatchRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("导入批次不存在：" + id));
        batch.setIsDeleted(true);
        importBatchRepo.save(batch);
        return ApiResponse.success(null);
    }
}
