package com.lusuoria.settlement.util;

import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.entity.SysUser;
import com.lusuoria.settlement.repository.SysUserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 当前登录账号关联的员工角色（Employee.role）判定，跟 SysUser.role（ADMIN/STAFF/AUDITOR/GUEST）
 * 无关。多个模块（红人结款、品牌方管理等）的"严格按员工角色限制访问"都基于这个判断，
 * 判定方式仿 ProgressReminderService.isManagementEmployee/isCurrentUserManagement。
 */
@Component
public class EmployeeRoleUtil {

    @Autowired private SysUserRepository sysUserRepo;
    @Autowired private EmployeeCache employeeCache;

    /** 当前登录账号关联员工的 role，未关联员工时返回 null */
    public String getCurrentEmployeeRole() {
        SysUser user = sysUserRepo.findByUsernameAndIsDeletedFalse(RoleUtil.getCurrentUsername()).orElse(null);
        if (user == null || user.getEmployeeId() == null) return null;
        Employee emp = employeeCache.findById(user.getEmployeeId());
        return emp != null ? emp.getRole() : null;
    }

    /**
     * 当前登录账号关联的员工 id（不管 SysUser.role 是什么，ADMIN/AUDITOR 账号只要关联了员工
     * 一样能拿到），未关联员工时返回 null。2026-07 新增，供"该记录的项目负责人/执行人员才能
     * 操作"这类按具体员工 id 判断的权限校验使用（不要跟 ProjectFieldVisibility.Context.employeeId
     * 混用——那个只对 STAFF 生效，语义不一样）。
     */
    public Long getCurrentEmployeeId() {
        SysUser user = sysUserRepo.findByUsernameAndIsDeletedFalse(RoleUtil.getCurrentUsername()).orElse(null);
        return user != null ? user.getEmployeeId() : null;
    }
}
