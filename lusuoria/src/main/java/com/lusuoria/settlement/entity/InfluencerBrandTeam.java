package com.lusuoria.settlement.entity;

import lombok.*;

import javax.persistence.*;

/**
 * 红人 - 品牌方 - 团队 关联表
 *
 * 一个红人可以关联多个"品牌方-团队"对（同一品牌方下也可以有多个不同团队）。
 * teamId 允许为空：品牌关联和团队是两件独立的事，有些品牌方下这个红人没有配团队。
 *
 * 用 id 关联品牌方和团队，而不是存文本名称，改名不影响已有关联。
 */
@Entity
@Table(name = "influencer_brand_teams",
       uniqueConstraints = @UniqueConstraint(columnNames = {"influencer_id", "brand_id", "team_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InfluencerBrandTeam extends BaseEntity {

    @Column(name = "influencer_id", nullable = false)
    private Long influencerId;

    @Column(name = "brand_id", nullable = false)
    private Long brandId;

    /** 团队（可为空——品牌关联和团队是独立的两件事，有些品牌下这个红人没有配团队） */
    @Column(name = "team_id")
    private Long teamId;
}
