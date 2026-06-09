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

    @Column(name = "real_name", length = 50)
    private String realName;

    /**
     * 角色：
     *   ADMIN   - 管理员，可执行老板审核，可管理账号
     *   STAFF   - 普通员工，可进行除老板审核外的所有操作
     *   AUDITOR - 审计/会计，只读 + 导出
     */
    @Column(name = "role", nullable = false, length = 20)
    private String role;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /**
     * 关联员工记录（可选）
     * 用于将系统账号与员工提成记录绑定
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private Employee employee;
}
