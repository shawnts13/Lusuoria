package com.lusuoria.settlement.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lusuoria.settlement.enums.RequirementSettlementStatus;
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

    /**
     * 默认项目负责人（可选，2026-07 新增）：新建需求时若创建人自己就是"项目负责人"角色，
     * 前端会自动带入创建人自己，也可以手动改成别人；纯粹起"默认值"作用——"红人合作跟踪"
     * 关联这条需求新建具体视频记录时，会以此作为项目负责人的默认候选（具体优先级规则见
     * CollaborationFormModal.onRequirementLinked：如果新建合作跟踪的人自己是"项目负责人"，
     * 仍然优先填创建人自己，只有创建人不是"项目负责人"时才采用这里的默认值）。
     */
    @Column(name = "default_project_manager_id", insertable = false, updatable = false)
    private Long defaultProjectManagerId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_project_manager_id")
    private Employee defaultProjectManager;

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
     * 合同链接（2026-07 新增）。品牌方"每次需求签一次合同"时，通过
     * InfluencerRequirementService.uploadContractLink() 写入，任何时候都可以上传/修改，
     * 不像 invoiceLink 那样要求需求先100%完成。品牌方"一年签一次合同"时这个字段不使用——
     * 那种品牌方的合同是团队级的（见 TeamContract，2026-08 起改成团队下所有红人共用同一份，
     * 不再按红人各自维护），改在"品牌方/红人团队管理"-"管理团队"里维护，前端会按品牌方的
     * Brand.contractCycleType 决定展示这个字段还是跳转去团队管理。
     */
    @Column(name = "contract_link", columnDefinition = "TEXT")
    private String contractLink;

    /**
     * 该需求是否已经跟管理层确认"不涉及合同"（2026-08-21 新增，仅对"每次需求签一次合同"的
     * 品牌方/团队有意义）。由 InfluencerRequirementService.confirmContractNotApplicable()
     * 写入，无需管理层审核（直接生效）——跟 CollaborationTracking.executorCostNotApplicable
     * 是同一类"确认不适用"标记。置为 true 后：
     *   - 前端"合同链接"列不再显示"—"，改显示"已跟管理层确认此需求不涉及合同"；
     *   - "待处理-合同上传逾期"（REQUIREMENT_CONTRACT_OVERDUE）批次不会再把这条需求算进候选，
     *     见 ProgressReminderService.runRequirementContractOverdue()。
     * 后续如果真的上传了合同链接（uploadContractLink()），这个标记会被自动清回 false——
     * 一条需求不可能同时"已上传合同"又"确认不涉及合同"。用包装类型 Boolean 而不是 boolean：
     * ddl-auto=update 给存量行新增这一列时是数据库层 NULL，用基本类型 boolean 读到 NULL
     * 会直接抛异常，这里统一按"NULL 等同 false（未确认）"处理。
     */
    @Column(name = "contract_not_applicable")
    private Boolean contractNotApplicable;

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
     * 结款状态（2026-08 新增）：null（还没有任何结款记录关联这个需求）/ 已添加结款记录
     * （关联的结款记录里最高只到"待付款"）/ 已付款（关联的结款记录里有任意一条已经"已付款"）。
     * 由 InfluencerRequirementService.refreshSettlementStatus() 统一维护，不要在别处直接设置。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_status", length = 30)
    private RequirementSettlementStatus settlementStatus;

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
     * 视频项目进度属于 已发布(未结算)/已加入客户未结算列表/客户已结算/已收到客户回款/折损
     * 这五个状态（2026-08-21 新增"已收到客户回款"）的记录数。
     * 瞬态字段，不落库，由 Controller 在列表/详情接口里批量查出来再赋值
     * （跟 CollaborationTracking.hasPendingDeleteRequest 的批量填充方式一致）。
     */
    @Transient
    private Integer completedCount;

    /**
     * 这条需求下所有条目的"已建立跟踪记录数"（关联到这条需求的红人合作跟踪记录数，不看
     * progress 状态，只要建立了就算，含折损——口径跟 progressDetail()/关联红人需求选择器
     * 用的 findByInternalRequirementNoAndIsDeletedFalse 一致），供"新建合作跟踪"按钮判断
     * 这条需求下是否还有条目没被建立跟踪记录（这个数達到 totalItemCount 时，说明每个条目的
     * 名额都已经有跟踪记录占上了，即使"需求完成进度"（completedCount，只看已发布/已结算/
     * 折损这几个终态）还没到100%，也不该再允许新建）。瞬态字段，不落库，由 Controller
     * 在列表接口里批量查出来再赋值。
     */
    @Transient
    private Integer establishedCount;

    /**
     * 当前是否有一条"待审核"的删除申请（2026-08 新增，跟"红人合作跟踪"的删除审核机制保持
     * 一致——删除不再直接生效，需要 ADMIN 在"待处理"模块同意后才真正删除）。前端据此显示
     * "审核中"、禁用删除按钮。瞬态字段，不落库，由 Controller 在返回列表/详情时批量查出来再赋值，
     * 跟 CollaborationTracking.hasPendingDeleteRequest 的批量填充方式一致。
     */
    @Transient
    private Boolean hasPendingDeleteRequest;
}
