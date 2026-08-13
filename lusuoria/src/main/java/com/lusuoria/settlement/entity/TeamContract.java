package com.lusuoria.settlement.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import javax.persistence.*;
import java.util.Date;

/**
 * 品牌方/团队级已签署合同（2026-08 新增，替代原来挂在红人身上的 InfluencerContract）。
 *
 * 背景：品牌方"一年签一次合同"的场景下，合同实际上是跟团队签的——团队下所有红人共用同一份
 * 合同，不是每个红人各自签一份。旧模型按 (红人,品牌方,团队) 维护，导致同一团队下每加一个
 * 红人都要重新上传一遍完全相同的合同文件；新模型直接按 (品牌方,团队) 维护，一个组合下可以有
 * 多条历史合同（不同年份可追溯），各自维护独立的有效期区间，团队下所有红人共享。
 *
 * teamId 允许为空：兼容"该品牌方下没有配团队层"的场景（截至2026-08，生产环境里"一年签一次
 * 合同"的品牌方都配了团队，这个场景暂不存在，但保留可能性——teamId 为空时合同直接归属整个
 * 品牌方，不需要额外建"占位团队"）。
 *
 * 不继承 BaseEntity、不做软删除：这是"新增一条/编辑一条"的独立 CRUD，删除就是直接删数据库行，
 * 方便手动清理很久以前的历史合同，跟原 InfluencerContract 是同一个约定。
 */
@Entity
@Table(name = "team_contracts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TeamContract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "brand_id", insertable = false, updatable = false)
    private Long brandId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    /** 团队，可为空（该品牌方下没有团队层的情况，见类注释） */
    @Column(name = "team_id", insertable = false, updatable = false)
    private Long teamId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private InfluencerTeam team;

    /** 合同生效日期（含当天） */
    @Temporal(TemporalType.DATE)
    @Column(name = "start_date", nullable = false)
    private Date startDate;

    /** 合同失效日期（含当天） */
    @Temporal(TemporalType.DATE)
    @Column(name = "end_date", nullable = false)
    private Date endDate;

    /** 合同链接（Google Drive） */
    @Column(name = "contract_link", columnDefinition = "TEXT", nullable = false)
    private String contractLink;
}
