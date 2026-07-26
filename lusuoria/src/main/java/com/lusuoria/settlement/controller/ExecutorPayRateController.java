package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.entity.ExecutorPayRate;
import com.lusuoria.settlement.repository.ExecutorPayRateRepository;
import com.lusuoria.settlement.util.EmployeeRoleUtil;
import com.lusuoria.settlement.util.RoleUtil;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 执行人员薪资梯度 - 按 (项目负责人, 执行人员) 独立维护。
 *
 * 权限收窄规则（2026-07 新增，跟"员工管理"/"执行人员管理"两个前端模块的拆分对应）：
 *  - STAFF 账号（无论员工角色是"项目负责人"还是"管理层"）：managerId 一律强制用登录账号
 *    自己关联的员工 id 覆盖请求里传的值，看不到、也改不了别人配的价；员工角色不是这两者之一
 *    的 STAFF 账号完全拒绝访问。
 *  - ADMIN：可以显式指定 managerId（"员工管理"页面配置某个执行人员的费率时，代表系统里
 *    唯一的"管理层"员工传过去），不传时默认取 {@link EmployeeCache#findManagementEmployee()}。
 */
@RestController
@RequestMapping("/api/executor-pay-rates")
public class ExecutorPayRateController {

    @Autowired private ExecutorPayRateRepository payRateRepo;
    @Autowired private EmployeeRoleUtil employeeRoleUtil;
    @Autowired private EmployeeCache employeeCache;

    /** 解析当前调用方实际生效的 managerId：STAFF 强制用自己的员工 id，ADMIN 可显式指定 */
    private Long resolveManagerId(Long requestedManagerId) {
        if (RoleUtil.isAdmin()) {
            if (requestedManagerId != null) return requestedManagerId;
            Employee management = employeeCache.findManagementEmployee();
            if (management == null) throw new RuntimeException("系统里还没有配置角色为\"管理层\"的员工");
            return management.getId();
        }
        String empRole = employeeRoleUtil.getCurrentEmployeeRole();
        if (!"项目负责人".equals(empRole) && !"管理层".equals(empRole)) {
            throw new RuntimeException("无权限维护执行人员薪资梯度");
        }
        Long ownEmployeeId = employeeRoleUtil.getCurrentEmployeeId();
        if (ownEmployeeId == null) throw new RuntimeException("当前账号未关联员工记录");
        return ownEmployeeId;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<List<ExecutorPayRate>> list(@RequestParam(required = false) Long managerId) {
        Long effectiveManagerId = resolveManagerId(managerId);
        return ApiResponse.success(payRateRepo.findByManagerIdAndIsDeletedFalse(effectiveManagerId));
    }

    /** 轻量存在性检查：供"红人合作跟踪"编辑表单在触发原子保存前判断这个 (负责人,执行人员) 是否已配置费率 */
    @GetMapping("/check")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF','AUDITOR')")
    public ApiResponse<Boolean> check(@RequestParam Long managerId, @RequestParam Long executorId) {
        boolean exists = payRateRepo.findByManagerIdAndExecutorIdAndIsDeletedFalse(managerId, executorId).isPresent();
        return ApiResponse.success(exists);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<ExecutorPayRate> save(@RequestBody SaveRequest req) {
        Long effectiveManagerId = resolveManagerId(req.getManagerId());
        if (req.getExecutorId() == null) throw new RuntimeException("请选择执行人员");

        ExecutorPayRate rate = payRateRepo
                .findByManagerIdAndExecutorIdAndIsDeletedFalse(effectiveManagerId, req.getExecutorId())
                .orElseGet(() -> ExecutorPayRate.builder()
                        .managerId(effectiveManagerId)
                        .executorId(req.getExecutorId())
                        .isDeleted(false)
                        .build());
        rate.setRateRealShotNew(req.getRateRealShotNew());
        rate.setRateAiNewMaterial(req.getRateAiNewMaterial());
        rate.setRateOldMaterialTier1(req.getRateOldMaterialTier1());
        rate.setRateOldMaterialTier2(req.getRateOldMaterialTier2());
        rate.setRateOldMaterialTier3(req.getRateOldMaterialTier3());
        rate.setOldMaterialMonthlyCap(req.getOldMaterialMonthlyCap());
        return ApiResponse.success(payRateRepo.save(rate));
    }

    @Data
    public static class SaveRequest {
        /** ADMIN 可显式指定；STAFF 传了也会被后端忽略，强制用自己的员工 id */
        private Long managerId;
        private Long executorId;
        private BigDecimal rateRealShotNew;
        private BigDecimal rateAiNewMaterial;
        private BigDecimal rateOldMaterialTier1;
        private BigDecimal rateOldMaterialTier2;
        private BigDecimal rateOldMaterialTier3;
        private BigDecimal oldMaterialMonthlyCap;
    }
}
