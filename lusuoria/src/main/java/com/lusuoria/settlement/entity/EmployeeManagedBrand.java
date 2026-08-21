package com.lusuoria.settlement.entity;

import lombok.*;

import javax.persistence.*;

/**
 * "项目管理员"负责管理的品牌方（2026-08-21 新增，"项目管理员"角色需求的一部分）。
 *
 * 独立成一张表，不挂在 Employee 身上——理由跟 ExecutorPayRateTier/CommissionBonusTier
 * 没有合并进 EmployeeCache 完全一样：EmployeeCache 里的 Employee 对象是多个并发请求共享的
 * 可变实例，把这种"运行时一对多子数据"挂上去，要么强迫每次缓存刷新都顺带 join 这张表（拖慢
 * 所有读 Employee 缓存的高频路径），要么在单个请求里直接改共享实例（并发下的真实竞态）。
 * 这里的关系形状也更接近 ExecutorPayRateTier 那种"两个实体之间的多对多关系表"，而不是"属于
 * 某个 Employee 的一个字段"，同样不适合直接挂在 Employee 上。
 *
 * 一个品牌方下面有哪些团队不需要在这里体现——"项目管理员负责这个品牌方"天然覆盖这个品牌方下
 * 全部团队，权限判断只需要按 brandId 匹配即可，见 EmployeeManagedBrandCache。
 */
@Entity
@Table(name = "employee_managed_brands", indexes = {
        // 覆盖"这个项目管理员负责哪些品牌方"（表单回显/权限判断按员工找品牌方列表）
        @Index(name = "idx_emb_employee_id", columnList = "employee_id"),
        // 覆盖"这个品牌方现在归哪个/哪些项目管理员负责"（提醒/待处理按品牌方反查负责人）
        @Index(name = "idx_emb_brand_id", columnList = "brand_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmployeeManagedBrand extends BaseEntity {

    /** 项目管理员的员工 id（必须是 role=项目负责人 且 projectAdminSince 不为空的员工，由 Controller 保存时校验） */
    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "brand_id", nullable = false)
    private Long brandId;
}
