package com.lusuoria.settlement.entity;

import lombok.*;

import javax.persistence.*;
import java.util.Date;

/**
 * 红人团队表
 * 红人表里 team_name 字段存储单个团队名称字符串（历史字段，已废弃）
 *
 * 2026-07 新增：团队归属唯一品牌方（brandId），承接"合同签订周期"的团队级覆盖设置——
 * 判定优先级见 Brand.contractCycleType 的注释：先看团队有没有覆盖，没有就退回品牌方级别配置。
 * brandId 迁移期允许为空（历史数据回填前），新建团队时后端会要求必填。
 */
@Entity
@Table(name = "influencer_teams")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InfluencerTeam extends BaseEntity {

    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    /** 归属的品牌方，团队必须唯一归属一个品牌方 */
    @Column(name = "brand_id")
    private Long brandId;

    /**
     * 特殊：即使品牌方整体是"一年签一次合同"，这个团队单独按"一次需求签一次合同"处理。
     * 只支持这一个方向的覆盖——品牌方是"每需求一签"时，团队不能覆盖成"一年一签"。
     * null 按 false 处理（沿用 Brand.requiresInvoice 的 null 安全默认思路：用 Boolean 包装类型
     * 而不是基本类型 boolean，这样 ddl-auto=update 给已有数据的表加这个新列时不需要在同一条
     * ALTER语句里带 DEFAULT，不会导致部署时因为 NOT NULL 约束报错）。
     */
    @Column(name = "force_per_requirement_contract")
    private Boolean forcePerRequirementContract;

    /**
     * 兜底默认合同到期日期（品牌方是"一年签一次合同"时才有意义）：团队下的红人如果本人没有
     * 单独关联生效中的合同（InfluencerContract），合同上传提醒按这个日期判断是否快到期。
     * 只需要到期日期这一个值（2026-07 曾误加过一个"生效起始日期"，用户确认过一直约定的是
     * 单个日期选择器，不是区间，已去掉）。
     */
    @Temporal(TemporalType.DATE)
    @Column(name = "default_contract_end_date")
    private Date defaultContractEndDate;

    /**
     * 合同签订周期判定优先级统一入口：先看团队有没有覆盖，没有就退回品牌方级别配置。
     * 只支持"品牌方一年一签、团队覆盖成每需求一签"这一个方向——品牌方本身已经是"每需求一签"
     * 时，团队覆盖与否不影响结果。team 为空（该记录没配团队）时完全按品牌方级别判断。
     */
    public static boolean isPerRequirementContract(Brand brand, InfluencerTeam team) {
        if (brand == null || brand.isPerRequirementContract()) return true;
        return team != null && Boolean.TRUE.equals(team.getForcePerRequirementContract());
    }
}
