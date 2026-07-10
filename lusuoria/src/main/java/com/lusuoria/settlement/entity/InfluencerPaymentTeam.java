package com.lusuoria.settlement.entity;

import lombok.*;

import javax.persistence.*;

/**
 * 红人结款 - 结款记录涉及的团队 关联表（2026-07 支持跨团队合并结款新增）。
 *
 * 一条结款记录可以涉及同一品牌方下的多个团队（也可能包含"不选团队"这个范围）。
 * 这里记的是"创建时选定的范围"，跟这条结款记录实际关联了哪些红人合作跟踪记录
 * 是两回事——范围在编辑（待付款状态）时不可再改，只能在这个范围内调整具体勾选的条目。
 *
 * teamId 允许为空：代表"不选团队"也是这次结算涉及的一个范围（参照 InfluencerBrandTeam 的同款设计）。
 */
@Entity
@Table(name = "influencer_payment_teams",
       uniqueConstraints = @UniqueConstraint(columnNames = {"influencer_payment_id", "team_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InfluencerPaymentTeam extends BaseEntity {

    @Column(name = "influencer_payment_id", nullable = false)
    private Long influencerPaymentId;

    /** 团队（可为空——代表"不选团队"也是这次结算涉及的一个范围） */
    @Column(name = "team_id")
    private Long teamId;
}
