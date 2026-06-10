package com.lusuoria.settlement.entity;

import lombok.*;

import javax.persistence.*;

/**
 * 红人团队表
 * 只维护团队名称，不与红人直接关联
 * 红人表里 team_name 字段存储单个团队名称字符串
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
}
