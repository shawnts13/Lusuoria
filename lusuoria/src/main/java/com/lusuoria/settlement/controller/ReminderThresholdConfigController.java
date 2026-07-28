package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.config.ReminderThresholdCache;
import com.lusuoria.settlement.dto.request.ReminderThresholdUpdateRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.entity.ReminderThresholdConfig;
import com.lusuoria.settlement.repository.ReminderThresholdConfigRepository;
import com.lusuoria.settlement.util.EmployeeRoleUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * 进度提醒阈值维护（2026-07-28 新增）：把 ProgressReminderService 里原本写死的各种阈值/分档
 * 边界抽出来给管理层维护，见 {@link ReminderThresholdConfig} 类注释。整个模块（包括查看）只对
 * 员工角色="管理层"开放，不是所有登录角色都能看——这些参数直接影响所有人能不能看到/什么时候
 * 看到进度提醒，属于管理层的运营决策，不是普通只读配置。
 */
@RestController
@RequestMapping("/api/reminder-threshold-configs")
public class ReminderThresholdConfigController {

    @Autowired private ReminderThresholdConfigRepository repo;
    @Autowired private ReminderThresholdCache cache;
    @Autowired private EmployeeRoleUtil employeeRoleUtil;

    private boolean canManage() {
        return "管理层".equals(employeeRoleUtil.getCurrentEmployeeRole());
    }

    @GetMapping
    public ApiResponse<List<ReminderThresholdConfig>> list() {
        if (!canManage()) return ApiResponse.error(403, "无权限查看进度提醒阈值配置");
        return ApiResponse.success(repo.findAllByOrderByCategoryAscSortOrderAsc());
    }

    @PutMapping("/{id}")
    public ApiResponse<ReminderThresholdConfig> update(@PathVariable Long id,
                                                        @Valid @RequestBody ReminderThresholdUpdateRequest req) {
        if (!canManage()) return ApiResponse.error(403, "无权限修改进度提醒阈值配置");
        ReminderThresholdConfig c = repo.findById(id)
                .orElseThrow(() -> new RuntimeException("配置项不存在"));
        c.setParamValue(req.getParamValue());
        c = repo.save(c);
        cache.refresh();
        return ApiResponse.success(c);
    }
}
