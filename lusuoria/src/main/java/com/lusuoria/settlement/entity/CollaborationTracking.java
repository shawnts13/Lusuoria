package com.lusuoria.settlement.entity;

import com.lusuoria.settlement.enums.CollaborationProgress;
import com.lusuoria.settlement.enums.InfluencerPaymentProgress;
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
     * 格式复用 ProjectNoGenerator：品牌方-红人团队-月份-红人账号-序号（没有团队时省略团队这一段）。
     * 月份固定用"创建时间当月"，不随后续填写的发布时间变化。
     * 联动生成项目订单时，项目订单直接复用这个值，不再重新生成。
     */
    @Column(name = "internal_project_no", unique = true)
    private String internalProjectNo;

    /**
     * 关联的"红人需求管理"内部需求编号（可空，2026-07 新增，存量数据一律为空）。
     * 通过"关联红人需求"选择器确认后由系统写入，同时会一并带入红人社媒完整名字/品牌方/
     * 红人团队/服务国家/市场/合作平台/项目视频类型这几个字段；Excel 导入时填了值也会走
     * 同一套校验（见 CollaborationTrackingService/InfluencerRequirementService）。
     */
    @Column(name = "internal_requirement_no")
    private String internalRequirementNo;

    /** 关联品牌方 id（直读列，不触发懒加载） */
    @Column(name = "brand_id", insertable = false, updatable = false)
    private Long brandId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id")
    private Brand brand;

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

    /**
     * 红人团队。跟随"品牌方"级联决定（同一个红人在不同品牌方下可能属于不同团队，
     * 也可能某个品牌方下压根没配团队），不再是红人库里那个已经废弃的单一 team_name 字段。
     * 具体校验规则见 CollaborationTrackingService.save()。
     */
    @Column(name = "team_id", insertable = false, updatable = false)
    private Long teamId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private InfluencerTeam team;

    /** 合作平台（多个，换行符分隔，如 "Instagram\nTikTok"） */
    @Column(name = "platform", columnDefinition = "TEXT")
    private String platform;

    /** 需求内容（即合作资源/需求描述） */
    @Column(name = "demand_content", columnDefinition = "TEXT")
    private String demandContent;

    /** 视频发布链接（前期可能为空） */
    @Column(name = "publish_link", columnDefinition = "TEXT")
    private String publishLink;

    /**
     * 视频发布时间（原名"发布时间"，字段本身/列名不变，只是显示名称改了，2026-07）。
     * 前期可能为空。写入规则（2026-07 起）：
     *   - Excel 批量导入：允许直接填，但只有"视频项目进度"达到 allowsPaymentProgress() 要求的
     *     三个阶段才能填，否则整行导入失败（校验见 CollaborationTrackingExcelHandler）；
     *   - 单条保存（新建/编辑表单）：仅 ADMIN 能编辑，其他角色提交的值会被忽略，保留数据库原值；
     *   - 其他角色只能通过"状态流转"接口在视频项目进度进入上述三个阶段、且当前为空时，
     *     由系统自动填上当天日期（见 CollaborationTrackingService.updateStatus）。
     */
    @Temporal(TemporalType.DATE)
    @Column(name = "publish_date")
    private Date publishDate;

    /** 进度 */
    @Enumerated(EnumType.STRING)
    @Column(name = "progress")
    private CollaborationProgress progress;

    /**
     * "进度"最近一次真正发生变化的时间（2026-07 新增，供进度滞留提醒批次计算"已经第几个
     * 工作日没流转"）。只有 progress 的值真的变了才更新——状态流转接口提交了同样的值不算
     * 变化，不会刷新这个时间。由 CollaborationTrackingService/PendingApprovalService 在
     * 每个实际改动 progress 的地方维护。
     */
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "progress_changed_at")
    private Date progressChangedAt;

    /**
     * 红人结款进度。默认空，只有"进度"达到 allowsPaymentProgress() 要求的三个阶段才允许设置值
     * （校验见 CollaborationTrackingService）。跟"进度"字段一样，新建时可选，
     * 编辑已有记录时锁定，只能通过"状态流转"接口修改。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "influencer_payment_progress")
    private InfluencerPaymentProgress influencerPaymentProgress;

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
     * 品牌方/团队/负责人/汇率等这类"跟客户方订单号联动"的逻辑，随着"项目订单"模块
     * 在 2026-07 整体废弃而一并移除；clientOrderId 现在就是一个普通的录入字段，
     * 不再触发任何自动生成/联动逻辑。
     */

    // ===== 基础财务字段（GUEST 之外都可见）=====
    /**
     * 红人视频制作与发布成本（美金）。2026-07 起改成严格数字类型，不再支持"价格待定"
     * 这类文本备注（之前是 TEXT，允许填备注；现在数据库层面就不允许存非数字内容了）。
     */
    @Column(name = "influencer_cost", precision = 15, scale = 2)
    private java.math.BigDecimal influencerCost;

    /** 客户合作价格（美金）。同上，2026-07 起改成严格数字类型 */
    @Column(name = "client_price", precision = 15, scale = 2)
    private java.math.BigDecimal clientPrice;

    /** 备注：记录一些特殊情况 */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // ===== 以下字段 2026-07 从"项目订单"模块迁移过来（该模块已废弃），权限可见性
    // 沿用原来的分级规则，具体由 ProjectFieldVisibility 在 Controller 层判定 =====

    /** 汇率（人民币/美元）。仅 ADMIN 可修改，其他角色只读展示 */
    @Column(name = "exchange_rate", precision = 10, scale = 4)
    private java.math.BigDecimal exchangeRate;

    /** 其他外部成本（人民币）。FULL 都能看/改；项目负责人仅自己负责的记录能看/改 */
    @Column(name = "other_external_cost", precision = 15, scale = 2)
    private java.math.BigDecimal otherExternalCost;

    /**
     * 外部成本备注：记录"其他外部成本"的来源和其他备注（比如物流成本等）。
     * 可见性/写权限跟 otherExternalCost 完全一致。
     */
    @Column(name = "other_external_cost_note", columnDefinition = "TEXT")
    private String otherExternalCostNote;

    /** 内部执行成本（人民币）。FULL 都能看/改；项目负责人/执行人员仅自己相关的记录能看/改 */
    @Column(name = "internal_execution_cost", precision = 15, scale = 2)
    private java.math.BigDecimal internalExecutionCost;

    /**
     * 该记录是否已确认"不涉及内部执行人员"（比如红人自己发布，不需要公司内部人员制作/发布）。
     * 在"设置内部执行成本"弹窗里选择这个选项后置为 true，之后视频项目进度/红人结款进度再怎么
     * 流转，也不会再对这条记录自动弹出该弹窗；如果后续确实需要执行人员，直接去编辑表单里
     * 手动选执行人员 + 填内部执行成本即可，不需要先把这个标记改回 false。
     * 用包装类型 Boolean 而不是 boolean：ddl-auto=update 给已有行新增这一列时，
     * 老数据这一列的值是数据库层面的 NULL，如果用基本类型 boolean，Hibernate 读到 NULL
     * 会直接抛 PropertyAccessException；用 Boolean 就能安全地把 NULL 当"未确认"处理。
     */
    @Column(name = "executor_cost_not_applicable")
    private Boolean executorCostNotApplicable;

    /** 项目毛利（美金，自动计算）。仅 FULL（ADMIN/管理层/财务）可见 */
    @Column(name = "gross_profit", precision = 15, scale = 2)
    private java.math.BigDecimal grossProfit;

    /** 可分配利润（美金，自动计算）。仅 FULL 可见 */
    @Column(name = "distributable_profit", precision = 15, scale = 2)
    private java.math.BigDecimal distributableProfit;

    /** 提成比例。FULL 可见可改；项目负责人仅自己负责的记录只读可见 */
    @Column(name = "commission_rate", precision = 5, scale = 4)
    private java.math.BigDecimal commissionRate;

    /** 提成金额（美金，自动计算）。FULL 可见；项目负责人仅自己负责的记录只读可见 */
    @Column(name = "commission_amount", precision = 15, scale = 2)
    private java.math.BigDecimal commissionAmount;

    /** 公司利润（美金，自动计算）。仅 FULL 可见 */
    @Column(name = "company_net_profit", precision = 15, scale = 2)
    private java.math.BigDecimal companyNetProfit;

    /** 公司利润（人民币，自动计算）。仅 FULL 可见 */
    @Column(name = "rmb_revenue", precision = 15, scale = 2)
    private java.math.BigDecimal rmbRevenue;

    /**
     * 关联的红人结款记录 id（非空表示已被纳入某条结款批次）。2026-07 红人结款模块重构新增。
     * 纳入批次时由 InfluencerPaymentService 写入，同时把当时的 influencerPaymentProgress
     * 快照到 preBatchPaymentProgress；移出批次（编辑"待付款"结款记录时取消勾选）时，
     * influencerPaymentProgress 从快照恢复，这两个字段一起清空。
     */
    @Column(name = "influencer_payment_id", insertable = false, updatable = false)
    private Long influencerPaymentId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "influencer_payment_id")
    private InfluencerPayment influencerPayment;

    /** 纳入结款批次前的 influencerPaymentProgress 快照，仅在 influencerPaymentId 非空时有意义 */
    @Enumerated(EnumType.STRING)
    @Column(name = "pre_batch_payment_progress")
    private InfluencerPaymentProgress preBatchPaymentProgress;

    /**
     * 当前是否有一条"待审核"的删除申请（有的话前端删除按钮要显示"审核中"）。
     * 瞬态字段，不落库，由 Controller 在返回列表时批量查出来再赋值。
     */
    @Transient
    private Boolean hasPendingDeleteRequest;

    /**
     * 当前是否有一条"待审核"的视频项目进度倒退申请（有的话前端状态流转要提示"审核中"，
     * 避免同一条记录被重复提交倒退申请）。瞬态字段，不落库，由 Controller 批量查出来再赋值。
     */
    @Transient
    private Boolean hasPendingRollbackRequest;

    /**
     * 这次保存/状态流转是不是触发了"设置内部执行成本"弹窗的条件。瞬态字段，不落库，
     * 由 CollaborationTrackingService 在 save()/updateStatus() 里按需设置，供前端据此
     * 弹出"设置内部执行成本"弹窗（见 CollaborationTrackingService 里对应的触发逻辑说明）。
     */
    @Transient
    private Boolean needExecutorCost;
}
