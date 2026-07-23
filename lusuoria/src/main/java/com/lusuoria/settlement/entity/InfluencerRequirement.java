package com.lusuoria.settlement.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import javax.persistence.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 红人需求管理：客户提的一批需求（通常来自一段聊天记录/邮件文本），结构化记录品牌方、红人、
 * 涉及哪几种"项目视频类型-合作平台"组合、各自数量和单价。"红人合作跟踪"新建具体视频记录时
 * 可以关联到这里的某一条需求（见 CollaborationTracking.internalRequirementNo），系统据此
 * 校验有没有超量、并自动带出品牌方/团队/国家/平台/视频类型这些字段。
 *
 * 去重/编号：internalRequirementNo 规则跟"红人合作跟踪"的 internalProjectNo 完全一致
 * （品牌方-红人团队-需求月份-红人账号-序号，无团队时省略团队段），新建时一次性生成，永久不变，
 * 复用同一个 ProjectNoGenerator，只是唯一性分配走 RequirementNoAllocator（查这张表而不是
 * 合作跟踪表）。
 */
@Entity
@Table(name = "influencer_requirements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InfluencerRequirement extends BaseEntity {

    @Column(name = "internal_requirement_no", unique = true)
    private String internalRequirementNo;

    /** 需求月份（yyyyMM），新建时默认当月，可手动改 */
    @Column(name = "requirement_month")
    private String requirementMonth;

    @Column(name = "brand_id", insertable = false, updatable = false)
    private Long brandId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id")
    private Brand brand;

    @Column(name = "team_id", insertable = false, updatable = false)
    private Long teamId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private InfluencerTeam team;

    @Column(name = "influencer_id", insertable = false, updatable = false)
    private Long influencerId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "influencer_id", nullable = false)
    private Influencer influencer;

    /**
     * 服务国家/市场（单选）：红人库本身允许维护多个，这里跟"红人合作跟踪"一样收窄成单选——
     * 一次需求不会涉及多个国家/市场。红人只维护了 0/1 个时自动带入，多个时前端必须选一个。
     */
    @Column(name = "country_market")
    private String countryMarket;

    /** 完整需求内容原文（自由格式文本，"提取需求内容"从这里解析结构化字段） */
    @Column(name = "full_requirement_content", columnDefinition = "TEXT")
    private String fullRequirementContent;

    /** 需求条目总数（各条目 videoCount 之和），保存条目时重新算好落库，不是实时计算 */
    @Column(name = "total_item_count")
    private Integer totalItemCount;

    /** 客户合作总价格（美金，各条目 clientUnitPrice*videoCount 之和） */
    @Column(name = "total_client_price", precision = 15, scale = 2)
    private BigDecimal totalClientPrice;

    /** 红人视频制作与发布总成本（美金，各条目 influencerUnitCostPrice*videoCount 之和） */
    @Column(name = "total_influencer_cost", precision = 15, scale = 2)
    private BigDecimal totalInfluencerCost;

    /** 备注 */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /**
     * Invoice 链接（该需求所有视频都实施完成后，统一上传一份 invoice，只能通过
     * InfluencerRequirementService.uploadInvoiceLink() 这个专门校验过的接口写入，
     * 不走普通的 save() 编辑表单，避免被意外覆盖）。
     */
    @Column(name = "invoice_link", columnDefinition = "TEXT")
    private String invoiceLink;

    /**
     * 需求完成进度达到100%（completedCount >= totalItemCount）那一刻的时间（2026-07 新增，
     * 供"Invoice逾期"提醒批次计算"完成后第几个工作日还没上传invoice"）。如果后续某条关联的
     * 合作跟踪记录被"进度倒退"审批通过、导致完成数重新低于总数，这个字段会被清空。由
     * InfluencerRequirementService.refreshCompletedAt() 统一维护，不要在别处直接设置。
     */
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "completed_at")
    private java.util.Date completedAt;

    /**
     * 需求条目集合，仅供 service 层落库时的级联增删改使用（在事务内操作）。
     * 不直接序列化给前端——open-in-view=false，事务外访问 LAZY 集合会抛异常；
     * 对外的条目列表统一走 InfluencerRequirementService 显式查询组装成响应 DTO，
     * 跟 CollaborationTracking 对 brand/influencer/team 等 LAZY 关联的处理方式一致。
     */
    @JsonIgnore
    @OneToMany(mappedBy = "requirement", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<InfluencerRequirementItem> items = new ArrayList<>();

    /**
     * "需求完成进度"的分子：关联到这条需求（按 internalRequirementNo）的"红人合作跟踪"记录中，
     * 视频项目进度属于 已发布(未结算)/已加入客户未结算列表/客户已结算/折损 这四个状态的记录数。
     * 瞬态字段，不落库，由 Controller 在列表/详情接口里批量查出来再赋值
     * （跟 CollaborationTracking.hasPendingDeleteRequest 的批量填充方式一致）。
     */
    @Transient
    private Integer completedCount;
}
