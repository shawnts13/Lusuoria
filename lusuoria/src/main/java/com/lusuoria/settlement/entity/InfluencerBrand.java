package com.lusuoria.settlement.entity;

import lombok.*;

import javax.persistence.*;

/**
 * 红人 - 品牌方 多对多中间表
 *
 * 用 id 关联而非文本存储品牌方名称，避免品牌方改名后红人记录里的旧名称
 * 与品牌方管理模块不同步（即"快照与改名脱钩"的问题）。
 */
@Entity
@Table(name = "influencer_brands",
       uniqueConstraints = @UniqueConstraint(columnNames = {"influencer_id", "brand_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InfluencerBrand extends BaseEntity {

    @Column(name = "influencer_id", nullable = false)
    private Long influencerId;

    @Column(name = "brand_id", nullable = false)
    private Long brandId;
}
