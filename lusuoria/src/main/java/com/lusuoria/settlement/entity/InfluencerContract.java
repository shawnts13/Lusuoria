package com.lusuoria.settlement.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import javax.persistence.*;

/**
 * 红人已签署合同（2026-07 新增，"红人管理"编辑弹窗里的"已签署合同"区块）：一个红人可以有多条，
 * 一年一条（品牌方"一年签一次合同"的场景靠这张表维护，"红人需求管理"那边同一红人同一年份的
 * 需求会直接展示这里对应年份的合同链接，见 InfluencerContractController.byInfluencerIds）。
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

    /** 合同年份，如 2026 */
    @Column(name = "contract_year", nullable = false)
    private Integer year;

    /** 合同链接（Google Drive） */
    @Column(name = "contract_link", columnDefinition = "TEXT", nullable = false)
    private String contractLink;
}
