package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.entity.DbBackupAlert;
import com.lusuoria.settlement.repository.DbBackupAlertRepository;
import com.lusuoria.settlement.service.impl.DbBackupService;
import com.lusuoria.settlement.util.EmployeeRoleUtil;
import com.lusuoria.settlement.util.RoleUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据库每日备份——"待处理"模块的失败提醒 + 手动重试，见 DbBackupService 类注释。
 * 可见/可操作范围：ADMIN 或员工角色="管理层"（2026-07-29 确认，跟"进度提醒"的受众口径一致）。
 */
@RestController
@RequestMapping("/api/db-backup")
public class DbBackupController {

    @Autowired private DbBackupAlertRepository alertRepo;
    @Autowired private DbBackupService backupService;
    @Autowired private EmployeeRoleUtil employeeRoleUtil;

    private boolean canManage() {
        return RoleUtil.isAdmin() || "管理层".equals(employeeRoleUtil.getCurrentEmployeeRole());
    }

    @GetMapping("/alert")
    public ApiResponse<DbBackupAlert> alert() {
        if (!canManage()) return ApiResponse.error(403, "无权限查看");
        return ApiResponse.success(alertRepo.findFirstByIsDeletedFalseOrderByIdDesc().orElse(null));
    }

    @PostMapping("/retry")
    public ApiResponse<String> retry() {
        if (!canManage()) return ApiResponse.error(403, "无权限执行此操作");
        DbBackupService.BackupResult result = backupService.runBackup();
        if (result.isSuccess()) {
            return ApiResponse.success(result.getMessage());
        }
        return ApiResponse.error(500, result.getMessage());
    }
}
