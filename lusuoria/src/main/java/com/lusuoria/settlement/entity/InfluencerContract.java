package com.lusuoria.settlement.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import javax.persistence.*;
import java.util.Date;

/**
 * 红人已签署合同（"红人管理"编辑弹窗里的"已签署合同"区块）：一个红人可以有多条，
 * 按 (品牌方, 团队) 各自独立维护有效期区间（品牌方"一年签一次合同"的场景靠这张表维护，
 * "红人需求管理"那边同一红人、同一品牌方、同一团队下，"需求月份"落在某条合同有效期内的
 * 需求会直接展示这条合同的链接，见 InfluencerContractController.byInfluencerIds）。
 *
 * 2026-07 从"一年一条"（仅 影响年份+链接）改成"按品牌方+团队各自维护任意有效期区间"：
 * 同一个红人可能同时给多个品牌方、多个团队干活，"1年1签"要精确到是哪个品牌方/团队的合同，
 * 而且合同起止日期不一定是完整自然年（如 2025-08-15 至 2026-08-14）。
 *
 * 不继承 BaseEntity、不做软删除：这是"新增一条/编辑一条"的独立 CRUD，不像
 * InfluencerRequirement.items 那样按父记录整体保存时全量 diff 替换集合，所以不需要
 * orphanRemoval 级联删除，也没有删除功能，isDeleted 字段没有意义。
 */
@Entity
@Table(name = "influencer_contracts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InfluencerContract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "influencer_id", insertable = false, updatable = false)
    private Long influencerId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "influencer_id", nullable = false)
    private Influencer influencer;

    /** 这份合同对应的品牌方（仅"一年签一次合同"的品牌方才会走这张表） */
    @Column(name = "brand_id", insertable = false, updatable = false)
    private Long brandId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    /** 这份合同对应的红人团队，可为空（该品牌方下这个红人没有配团队的情况） */
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
