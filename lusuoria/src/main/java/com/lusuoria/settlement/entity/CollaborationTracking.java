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
 * 去重键：influencerId + publishLink + publishDate（三者完全相同视为重复）
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

    /**
     * 内部项目编号（自动生成，创建时一次性生成，前端只读不可编辑）。
     * 格式复用 ProjectNoGenerator：品牌-月份-红人账号-序号。
     * 月份固定用"创建时间当月"，不随后续填写的发布时间变化。
     * 联动生成项目订单时，项目订单直接复用这个值，不再重新生成。
     */
    @Column(name = "internal_project_no", unique = true)
    private String internalProjectNo;

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

    /**
     * 关联红人 id（真正的外键）。
     * 之前这里直接存红人的"社媒完整名字"文本，红人一旦改名，
     * 已有的跟踪记录就变成了孤儿（找不到人、没法再编辑）。改成存 id 以后，
     * 红人改名完全不受影响，页面上展示的名字始终是当前最新的名字。
     */
    @Column(name = "influencer_id", insertable = false, updatable = false)
    private Long influencerId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "influencer_id", nullable = false)
    private Influencer influencer;

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

    /** 项目视频类型：实拍新视频 / 实拍新图片 / AI新素材 / 旧素材重发 */
    @Enumerated(EnumType.STRING)
    @Column(name = "video_type")
    private VideoType videoType;

    /**
     * 采买旧视频的原链接。只有"项目视频类型"为"旧素材重发"时才涉及填写，其他情况必须为空
     * （由 CollaborationTrackingService 在保存时校验）。全表唯一，
     * 去重时会先做归一化（去空格、统一 http/https、去 www.、去尾部斜杠、去查询参数），
     * 避免同一视频不同链接形式被当成两个不同视频重复采买。
     */
    @Column(name = "old_material_source_link", columnDefinition = "TEXT")
    private String oldMaterialSourceLink;

    /**
     * oldMaterialSourceLink 归一化后的版本，仅供后端查重用，不通过 API 暴露。
     * 由 CollaborationTrackingService 在保存时自动计算填充。
     */
    @JsonIgnore
    @Column(name = "old_material_source_link_normalized", unique = true)
    private String oldMaterialSourceLinkNormalized;

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

    /** 内部执行人员（员工，改了会自动同步到已生成的项目订单） */
    @Column(name = "executor_id", insertable = false, updatable = false)
    private Long executorId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "executor_id")
    private Employee executor;

    /**
     * 本跟踪记录已生成的项目订单 id（防止重复生成）。
     * 为空表示还未生成过项目订单。
     */
    @Column(name = "generated_project_order_id")
    private Long generatedProjectOrderId;

    // ===== 基础财务字段（GUEST 之外都可见）=====
    /** 红人视频制作与发布成本（美金） */
    @Column(name = "influencer_cost", columnDefinition = "TEXT")
    private String influencerCost;

    /** 客户合作价格（美金） */
    @Column(name = "client_price", columnDefinition = "TEXT")
    private String clientPrice;

    /**
     * 当前是否有一条"待审核"的删除申请（有的话前端删除按钮要显示"审核中"）。
     * 瞬态字段，不落库，由 Controller 在返回列表时批量查出来再赋值。
     */
    @Transient
    private Boolean hasPendingDeleteRequest;
}
