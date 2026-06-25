package com.lusuoria.settlement.util;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 角色权限工具类
 *
 * 权限矩阵：
 *   ADMIN   - 老板：全部字段可见 + 写操作 + 老板审核 + 账号管理 + 提成修改 + 汇率修改
 *   AUDITOR - 会计：全部字段可见（含敏感字段）+ 只读 + 导出
 *   STAFF   - 普通员工：不可见敏感字段 + 写操作（除提成比例/老板审核/汇率）
 *   GUEST   - 访客：不可见敏感字段 + 只读
 *
 * 敏感字段 = 收入、利润、提成比例、提成金额等（应付金额不属于敏感字段）
 */
public class RoleUtil {

    /**
     * 是否可以查看敏感财务字段（收入、利润、提成）
     * 仅 ADMIN 和 AUDITOR 可见
     */
    public static boolean canViewSensitiveFields() {
        String role = getCurrentRole();
        return "ADMIN".equals(role) || "AUDITOR".equals(role);
    }

    /**
     * 是否有写操作权限（新建/编辑/删除/导入）
     * ADMIN 和 STAFF 有写权限
     */
    public static boolean canWrite() {
        String role = getCurrentRole();
        return "ADMIN".equals(role) || "STAFF".equals(role);
    }

    /**
     * 是否可以修改员工的提成比例、奖金等字段
     * 仅 ADMIN
     */
    public static boolean canEditCommission() {
        return "ADMIN".equals(getCurrentRole());
    }

    /**
     * 是否可以修改项目订单的汇率字段
     * 仅 ADMIN（项目负责人/执行人员等其他角色只能查看，不能修改）
     */
    public static boolean canEditExchangeRate() {
        return "ADMIN".equals(getCurrentRole());
    }

    /**
     * 是否可以执行老板审核
     * 仅 ADMIN
     */
    public static boolean canApprove() {
        return "ADMIN".equals(getCurrentRole());
    }

    public static String getCurrentRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return "";
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .findFirst()
                .orElse("");
    }

    /** 获取当前登录用户名（审计留痕用，如汇率修改记录） */
    public static String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return "未知用户";
        String name = auth.getName();
        return (name == null || name.isEmpty()) ? "未知用户" : name;
    }
}
