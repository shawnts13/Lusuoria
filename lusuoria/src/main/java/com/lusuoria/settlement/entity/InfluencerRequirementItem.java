package com.lusuoria.settlement.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lusuoria.settlement.enums.VideoType;
import lombok.*;

import javax.persistence.*;
import java.math.BigDecimal;

/**
 * 红人需求条目：一个需求下"项目视频类型-合作平台"这个组合要拍多少条、单价多少。
 * 同一需求内 (videoType, platform) 组合不允许重复——platform 存储时按字典序排序后
 * 换行拼接（不像 CollaborationTracking.platform 那样原样保留选择顺序），这样比较两个
 * 条目是不是"同一个平台组合"时不用管前端选择的先后顺序。
 */
@Entity
@Table(name = "influencer_requirement_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InfluencerRequirementItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "requirement_id", insertable = false, updatable = false)
    private Long requirementId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requirement_id", nullable = false)
    private InfluencerRequirement requirement;

    @Enumerated(EnumType.STRING)
    @Column(name = "video_type")
    private VideoType videoType;

    /** 合作平台（多选，按字典序排序后换行拼接，见类注释） */
    @Column(name = "platform", columnDefinition = "TEXT")
    private String platform;

    @Column(name = "video_count")
    private Integer videoCount;

    /** 客户合作单价（美金） */
    @Column(name = "client_unit_price", precision = 15, scale = 2)
    private BigDecimal clientUnitPrice;

    /** 红人视频制作与发布单价成本（美金） */
    @Column(name = "influencer_unit_cost_price", precision = 15, scale = 2)
    private BigDecimal influencerUnitCostPrice;
}
