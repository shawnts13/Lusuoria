package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.dto.response.PayslipDetailResponse;
import com.lusuoria.settlement.dto.response.PayslipRowResponse;
import com.lusuoria.settlement.service.impl.PayslipService;
import com.lusuoria.settlement.util.EmployeeRoleUtil;
import com.lusuoria.settlement.util.RoleUtil;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 工资单：管理层（ADMIN 或员工角色="管理层"）能看到全体员工按月的工资单、设置奖金/确认；
 * 普通员工只能看自己的那份。角色收窄判断复用 EmployeeRoleUtil，风格跟 ExecutorPayRateController 一致。
 */
@RestController
@RequestMapping("/api/payslips")
public class PayslipController {

    @Autowired private PayslipService payslipService;
    @Autowired private EmployeeRoleUtil employeeRoleUtil;

    private boolean isManagement() {
        return RoleUtil.isAdmin() || "管理层".equals(employeeRoleUtil.getCurrentEmployeeRole());
    }

    private void requireManagement() {
        if (!isManagement()) throw new RuntimeException("无权限管理工资单");
    }

    /** 管理层视角的员工列表（不含管理层自己，见 /management） */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<List<PayslipRowResponse>> list(
            @RequestParam String yearMonth,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "USD") String currency) {
        requireManagement();
        return ApiResponse.success(payslipService.listForMonth(yearMonth, role, currency));
    }

    /** 管理层自己那一行，单独一个接口（不在角色筛选列表里） */
    @GetMapping("/management")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<PayslipRowResponse> management(
            @RequestParam String yearMonth, @RequestParam(defaultValue = "USD") String currency) {
        requireManagement();
        return ApiResponse.success(payslipService.managementRow(yearMonth, currency));
    }

    /** 当前登录账号自己的工资单 */
    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF','AUDITOR')")
    public ApiResponse<PayslipDetailResponse> me(
            @RequestParam String yearMonth, @RequestParam(defaultValue = "USD") String currency) {
        Long employeeId = employeeRoleUtil.getCurrentEmployeeId();
        if (employeeId == null) throw new RuntimeException("当前账号未关联员工记录，无法查看个人工资单");
        return ApiResponse.success(payslipService.detail(employeeId, yearMonth, currency));
    }

    /** 明细弹窗数据：本人或管理层可查看 */
    @GetMapping("/{employeeId}/detail")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF','AUDITOR')")
    public ApiResponse<PayslipDetailResponse> detail(
            @PathVariable Long employeeId,
            @RequestParam String yearMonth, @RequestParam(defaultValue = "USD") String currency) {
        boolean isSelf = employeeId.equals(employeeRoleUtil.getCurrentEmployeeId());
        if (!isSelf && !isManagement()) throw new RuntimeException("无权限查看该员工的工资单");
        return ApiResponse.success(payslipService.detail(employeeId, yearMonth, currency));
    }

    @PostMapping("/{employeeId}/extra-bonus")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<Void> setExtraBonus(@PathVariable Long employeeId, @RequestBody ExtraBonusRequest req) {
        requireManagement();
        payslipService.setExtraBonus(employeeId, req.getYearMonth(), req.getAmount(), req.getCurrency());
        return ApiResponse.success();
    }

    @PostMapping("/{employeeId}/legal-salary")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<Void> setLegalSalary(@PathVariable Long employeeId, @RequestBody LegalSalaryRequest req) {
        requireManagement();
        payslipService.setLegalSalary(employeeId, req.getYearMonth(), req.getAmountRmb());
        return ApiResponse.success();
    }

    @PostMapping("/{employeeId}/confirm")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<Void> confirm(@PathVariable Long employeeId, @RequestBody MonthRequest req) {
        requireManagement();
        payslipService.confirm(employeeId, req.getYearMonth());
        return ApiResponse.success();
    }

    @PostMapping("/{employeeId}/unconfirm")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<Void> unconfirm(@PathVariable Long employeeId, @RequestBody MonthRequest req) {
        requireManagement();
        payslipService.unconfirm(employeeId, req.getYearMonth());
        return ApiResponse.success();
    }

    @Data
    public static class ExtraBonusRequest {
        private String yearMonth;
        /** 传 null 表示清除已设置的奖金 */
        private BigDecimal amount;
        /** "USD"/"RMB"，amount 非空时必填 */
        private String currency;
    }

    @Data
    public static class LegalSalaryRequest {
        private String yearMonth;
        private BigDecimal amountRmb;
    }

    @Data
    public static class MonthRequest {
        private String yearMonth;
    }
}
