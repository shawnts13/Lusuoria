package com.lusuoria.settlement.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 展示用：红人关联的一条"品牌方-团队"对（非持久化实体，仅用于 API 响应）。
 * 由 InfluencerController 查询 InfluencerBrandTeam 中间表后组装，塞进
 * Influencer.brandTeamPairs 字段里。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InfluencerBrandTeamView {
    private Long brandId;
    private String brandName;
    /** 可能为空：品牌关联和团队是独立的两件事 */
    private Long teamId;
    private String teamName;
}
