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
     * 团队级"合同签订周期"覆盖：三态——null=不覆盖，跟随品牌方整体设置；true=覆盖成"一次
     * 需求签一次合同"；false=覆盖成"一年签一次合同"。2026-08-21 起支持双向覆盖（原来只支持
     * "品牌方一年一签、团队覆盖成每需求一签"这一个方向，Shawn 反馈需要反过来也能覆盖——
     * 品牌方整体是"每需求一签"，个别团队特殊按"一年签一次合同"处理），跟同一个实体上
     * involvesCorporateInvoice 字段的双向覆盖是同一个思路，见 isPerRequirementContract()。
     */
    @Column(name = "force_per_requirement_contract")
    private Boolean forcePerRequirementContract;

    /**
     * 兜底默认合同到期日期（品牌方是"一年签一次合同"时才有意义）：这个团队如果没有生效中的
     * 团队级合同（TeamContract，2026-08 起团队下所有红人共用同一份合同，不再按红人各自维护），
     * 合同上传提醒按这个日期判断是否快到期。只需要到期日期这一个值（2026-07 曾误加过一个
     * "生效起始日期"，用户确认过一直约定的是单个日期选择器，不是区间，已去掉）。
     */
    @Temporal(TemporalType.DATE)
    @Column(name = "default_contract_end_date")
    private Date defaultContractEndDate;

    /**
     * 合同签订周期判定优先级统一入口：团队有显式覆盖（forcePerRequirementContract 非 null）
     * 就用团队的值（不管是 true 还是 false，都直接覆盖品牌方整体设置，双向覆盖，2026-08-21
     * 起支持——之前只支持"品牌方一年一签、团队覆盖成每需求一签"单方向）；团队没配置
     * （null）时退回品牌方级别判断。team 为空（该记录没配团队）时完全按品牌方级别判断。
     */
    public static boolean isPerRequirementContract(Brand brand, InfluencerTeam team) {
        if (team != null && team.getForcePerRequirementContract() != null) {
            return team.getForcePerRequirementContract();
        }
        return brand == null || brand.isPerRequirementContract();
    }

    /**
     * 是否涉及"公对公发票"（2026-08 新增，"红人结款"上传发票流程用）。三态：null=没有单独
     * 配置，跟随品牌方默认（{@link Brand#getDefaultInvolvesCorporateInvoice()}）；显式
     * true/false 则完全覆盖品牌方默认值（两个方向都支持，不像合同签订周期那样只能单向覆盖）——
     * 团队有配置就以团队为准，见 {@link #involvesCorporateInvoice(Brand, InfluencerTeam)}。
     */
    @Column(name = "involves_corporate_invoice")
    private Boolean involvesCorporateInvoice;

    /**
     * 是否涉及公对公发票判定优先级统一入口：团队有显式配置（非 null）就用团队的值（不管是
     * true 还是 false，都直接覆盖品牌方默认，双向覆盖）；团队没配置时落回品牌方默认值
     * （null/false 都按"不涉及"处理）。team 为空（该记录没配团队，比如 TEMU海外/ATOMS
     * 这种品牌方底下没有团队的情况）时完全按品牌方默认值判断。
     */
    public static boolean involvesCorporateInvoice(Brand brand, InfluencerTeam team) {
        if (team != null && team.getInvolvesCorporateInvoice() != null) {
            return team.getInvolvesCorporateInvoice();
        }
        return brand != null && Boolean.TRUE.equals(brand.getDefaultInvolvesCorporateInvoice());
    }
}
