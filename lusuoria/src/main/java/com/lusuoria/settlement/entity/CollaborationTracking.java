package com.lusuoria.settlement.entity;

import com.lusuoria.settlement.enums.CollaborationProgress;
import com.lusuoria.settlement.enums.VideoType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import javax.persistence.*;
import java.util.Date;

/**
 * 红人合作跟踪
 *
 * 记录每次视频合作从洽谈到结算的全过程。
 * teamName / countryMarket 为保存时从红人库拷贝的快照，不随红人库变化。
 *
 * 去重键：accountName + publishLink + publishDate（三者完全相同视为重复）
 * 当 publishLink 和 publishDate 都为空时不参与去重。
 */
@Entity
@Table(name = "collaboration_trackings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CollaborationTracking extends BaseEntity {

    /** 关联品牌方 id（直读列，不触发懒加载） */
    @Column(name = "brand_id", insertable = false, updatable = false)
    private Long brandId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id")
    private Brand brand;

    /** 红人团队（快照，保存时从红人库拷贝） */
    @Column(name = "team_name")
    private String teamName;

    /** 服务国家/市场（快照，保存时从红人库拷贝） */
    @Column(name = "country_market")
    private String countryMarket;

    /** 红人ID（达人名称，必须是红人库里存在的） */
    @Column(name = "account_name", nullable = false)
    private String accountName;

    /** 合作平台（多个，换行符分隔，如 "Instagram\nTikTok"） */
    @Column(name = "platform", columnDefinition = "TEXT")
    private String platform;

    /** 需求内容（即合作资源/需求描述） */
    @Column(name = "demand_content", columnDefinition = "TEXT")
    private String demandContent;

    /** 视频发布链接（前期可能为空） */
    @Column(name = "publish_link", columnDefinition = "TEXT")
    private String publishLink;

    /** 发布时间（前期可能为空） */
    @Temporal(TemporalType.DATE)
    @Column(name = "publish_date")
    private Date publishDate;

    /** 进度 */
    @Enumerated(EnumType.STRING)
    @Column(name = "progress")
    private CollaborationProgress progress;

    /** 项目视频类型：实拍新视频 / AI新素材 / 旧素材重发 */
    @Enumerated(EnumType.STRING)
    @Column(name = "video_type")
    private VideoType videoType;

    /** 客户方的项目订单（即客户系统订单ID，前期可能为空） */
    @Column(name = "client_order_id")
    private String clientOrderId;

    /** 客户方付款批次 */
    @Column(name = "client_payment_batch")
    private String clientPaymentBatch;

    /** 项目负责人（员工，生成项目订单时自动带过去） */
    @Column(name = "project_manager_id", insertable = false, updatable = false)
    private Long projectManagerId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_manager_id")
    private Employee projectManager;

    /**
     * 本跟踪记录已生成的项目订单 id（防止重复生成）。
     * 为空表示还未生成过项目订单。
     */
    @Column(name = "generated_project_order_id")
    private Long generatedProjectOrderId;

    // ===== 敏感字段（仅 ADMIN / AUDITOR）=====
    /** 红人视频制作与发布成本（美金） */
    @Column(name = "influencer_cost", columnDefinition = "TEXT")
    private String influencerCost;

    /** 客户合作价格（美金） */
    @Column(name = "client_price", columnDefinition = "TEXT")
    private String clientPrice;
}
