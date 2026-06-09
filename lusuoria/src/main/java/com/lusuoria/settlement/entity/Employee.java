package com.lusuoria.settlement.entity;

import javax.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "employees")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Employee extends BaseEntity {

    @Column(name = "name", nullable = false)
    private String name;                     // 姓名

    @Column(name = "role")
    private String role;                     // 角色：项目负责人 / 执行人员

    @Column(name = "email", unique = true)
    private String email;

    // 默认提成比例（可在项目层面覆盖）
    @Column(name = "default_commission_rate", precision = 5, scale = 4)
    private BigDecimal defaultCommissionRate; // 如 0.25 表示 25%

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}