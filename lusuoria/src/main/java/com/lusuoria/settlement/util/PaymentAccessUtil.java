package com.lusuoria.settlement.util;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 红人结款模块 - 访问权限判定。
 *
 * 严格按登录账号关联的员工角色（Employee.role）判断，跟 SysUser.role（ADMIN/STAFF/
 * AUDITOR/GUEST）无关——即使是 ADMIN(老板)/AUDITOR(会计) 登录，如果没有关联"管理层/
 * 财务/法务"这三个员工角色之一的员工记录，也完全看不到这个模块（产品明确要求，2026-07）。
 */
@Component
public class PaymentAccessUtil {

    private static final Set<String> VIEW_ROLES = new HashSet<>(Arrays.asList("管理层", "财务", "法务"));
    private static final String MANAGE_ROLE = "管理层";

    @Autowired private EmployeeRoleUtil employeeRoleUtil;

    /** 是否可见（列表/详情/导出）：员工角色 = 管理层/财务/法务 */
    public boolean canView() {
        return VIEW_ROLES.contains(employeeRoleUtil.getCurrentEmployeeRole());
    }

    /** 是否可写（新增/编辑/状态流转/删除/强改汇率）：仅员工角色 = 管理层 */
    public boolean canManage() {
        return MANAGE_ROLE.equals(employeeRoleUtil.getCurrentEmployeeRole());
    }
}
