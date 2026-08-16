package com.lusuoria.settlement.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lusuoria.settlement.enums.VideoType;
import lombok.*;

import javax.persistence.*;
import java.math.BigDecimal;

/**
 * 红人需求条目：一个需求下"项目视频类型-合作平台"这个组合要拍多少条、单价多少。
 * (videoType, platform) 组合本身允许重复——同样的类型/平台可能有不同的单价，需要拆成多个
 * 条目分别记录；但 (videoType, platform, clientUnitPrice, influencerUnitCostPrice) 这四个
 * 字段全部相同则不允许重复（InfluencerRequirementService.validateNoDuplicateItemKeys()
 * 校验），因为这四个字段是判断"一条合作跟踪记录属于哪个条目"的全部依据，完全相同就没法区分。
 * platform 存储时按字典序排序后换行拼接（不像 CollaborationTracking.platform 那样原样保留
 * 选择顺序），这样比较两个条目是不是"同一个平台组合"时不用管前端选择的先后顺序。
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
