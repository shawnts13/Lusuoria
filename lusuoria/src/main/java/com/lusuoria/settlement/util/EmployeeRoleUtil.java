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
}
