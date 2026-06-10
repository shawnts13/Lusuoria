package com.lusuoria.settlement.entity;

import lombok.*;

import javax.persistence.*;

@Entity
@Table(name = "sys_users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SysUser extends BaseEntity {

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "password", nullable = false)
    private String password;

    /**
     * 角色：
     *   ADMIN   - 老板/管理员：全部权限 + 老板审核 + 账号管理
     *   STAFF   - 普通员工：可写操作，看不到收入/利润/提成
     *   AUDITOR - 审计/会计：只读 + 导出，可看全部财务数据
     *   GUEST   - 访客：只读，看不到收入/利润/提成
     */
    @Column(name = "role", nullable = false, length = 20)
    private String role;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /**
     * 关联员工 ID（直接读列，不触发懒加载）
     * toResponse() 用这个字段查 EmployeeCache，零额外 SQL
     */
    @Column(name = "employee_id", insertable = false, updatable = false)
    private Long employeeId;

    /**
     * 关联员工记录（可选）
     * 关联后右上角显示名称将使用员工姓名，否则显示用户名
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private Employee employee;
}
