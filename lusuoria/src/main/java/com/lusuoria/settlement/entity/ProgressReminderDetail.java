package com.lusuoria.settlement.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 进度提醒明细行（2026-07 新增），BRAND_MONTH_END_PAYMENT_DUE 类别没有明细（本身就是一句
 * 完整文案），其余类别都有。
 *
 * 每行对应一条命中的记录（大多数类别是红人合作跟踪记录，REQUIREMENT_INVOICE_OVERDUE/
 * REQUIREMENT_CONTRACT_OVERDUE 是需求，INFLUENCER_PAYMENT_DUE 是结款记录），在跑批当时把
 * 展示要用的字段整体快照下来（而不是存一个 id 现查现拼），这样当天不管看多少次"待处理-进度
 * 提醒"或者弹窗，看到的数字和明细列表都严格对得上，不会因为白天有人改了底层数据而对不上。
 */
@Entity
@Table(name = "progress_reminder_details")
@Getter
@Setter
public class ProgressReminderDetail extends BaseEntity {

    /** 所属的 ProgressReminder id（不建外键关联对象，纯粹的展示明细，不需要懒加载） */
    @Column(name = "reminder_id", nullable = false)
    private Long reminderId;

    /** 原始红人合作跟踪记录 id，仅供以后可能的"跳转查看"使用 */
    @Column(name = "tracking_id", nullable = false)
    private Long trackingId;

    @Column(name = "internal_project_no")
    private String internalProjectNo;

    @Column(name = "brand_name")
    private String brandName;

    @Column(name = "team_name")
    private String teamName;

    @Column(name = "account_name")
    private String accountName;

    @Column(name = "demand_content", columnDefinition = "TEXT")
    private String demandContent;

    /**
     * 红人视频制作与发布成本（美金）快照。语义按类别重新解释：PM_EXECUTOR_PROGRESS_STALL/
     * FINANCE_PROGRESS_STALL 是单条红人合作跟踪记录的成本；REQUIREMENT_INVOICE_OVERDUE
     * 是整个需求的红人视频制作与发布总成本；INFLUENCER_PAYMENT_DUE 是这条结款记录的
     * 应付金额（美金）。
     */
    @Column(name = "influencer_cost", precision = 15, scale = 2)
    private BigDecimal influencerCost;

    /**
     * 视频项目进度中文标签快照（不存枚举，因为跑批之后这条记录的进度可能会变）。
     * INFLUENCER_PAYMENT_DUE 复用成这条结款记录的付款状态标签（固定是"待付款"，
     * 只有待付款的记录才会生成这类提醒）。
     */
    @Column(name = "progress_label")
    private String progressLabel;

    /**
     * 视频发布时间快照。INFLUENCER_PAYMENT_DUE 复用成这条结款记录的对账日期
     * （可能为空——对账日期本身是可选字段）。
     */
    @Temporal(TemporalType.DATE)
    @Column(name = "publish_date")
    private Date publishDate;

    /**
     * 历史 NOT NULL 列，含义按类别重新解释（2026-07）：
     *   - COLLAB_PAYMENT_DUE：结款周期（天数），根据品牌方配置算出来的。2026-08 起口径按
     *     品牌方是否需要invoice区分：需要invoice的，按这条记录关联需求的实际可结款成本
     *     （不含折损）分档；不需要invoice的，仍按单笔红人成本分档——跟
     *     InfluencerPaymentService.computeCycleInfo 保持同一套口径（共用
     *     InfluencerRequirementService.fetchPaymentInfo）。
     *   - PM_EXECUTOR_PROGRESS_STALL/FINANCE_PROGRESS_STALL："提醒阈值（工作日）"——
     *     超过多少个工作日没流转就算滞留，跑批当时从"进度提醒阈值维护"读到的实际值。
     *   - REQUIREMENT_INVOICE_OVERDUE/REQUIREMENT_CONTRACT_OVERDUE："需求条目总数"（不是
     *     天数）——这两类的"提醒阈值（工作日）"改存在 {@link #thresholdDays} 里，不是这个字段。
     *   - CONTRACT_EXPIRING_SOON：合同到期提醒窗口天数（跑批当时从"进度提醒阈值维护"读到的
     *     EXPIRY_WINDOW_DAYS，不是固定30）。
     *   - INFLUENCER_PAYMENT_DUE："合作数量"（这条结款记录勾选的红人合作跟踪条目数）。
     */
    @Column(name = "cycle_days", nullable = false)
    private Integer cycleDays;

    /**
     * 最迟结款日。COLLAB_PAYMENT_DUE：不需要invoice时 = 视频发布时间 + 结款周期；需要invoice时
     * = {@link #requirementCompletedAt}（需求完成进度达到100%的时间）+ 结款周期，不是从视频
     * 发布时间起算，见该字段注释。INFLUENCER_PAYMENT_DUE 复用成这条结款记录的预计付款日
     * （跟这类提醒的分档依据是同一个日期）。
     */
    @Temporal(TemporalType.DATE)
    @Column(name = "deadline_date", nullable = false)
    private Date deadlineDate;

    /**
     * "超出天数"（2026-07 新增，PM_EXECUTOR_PROGRESS_STALL/FINANCE_PROGRESS_STALL/
     * REQUIREMENT_INVOICE_OVERDUE 这3类新提醒专用）。老两类不设置，前端该列展示"—"。
     */
    @Column(name = "overdue_days")
    private Integer overdueDays;

    /**
     * REQUIREMENT_INVOICE_OVERDUE 专用：关联的红人需求 id/内部需求编号，供"查看详情"跳转到
     * "红人需求管理"模块（这一类的 trackingId 会填成该需求下某一条关联合作跟踪记录的 id
     * 占位——trackingId 是历史 NOT NULL 列，不能留空——但"查看详情"实际跳转按这两个字段来，
     * 不用 trackingId）。INFLUENCER_PAYMENT_DUE 复用 requirementId 存这条结款记录自己的 id
     * （不是需求 id），internalRequirementNo 复用存结款单号——"查看详情"跳转到"红人结款"
     * 模块按结款单号定位，同样不用 trackingId。
     */
    @Column(name = "requirement_id")
    private Long requirementId;

    /**
     * 2026-08 起 COLLAB_PAYMENT_DUE 也会给需要invoice的记录填这个字段（纯展示用，不影响
     * "查看详情"跳转——那个类别的跳转固定按 internalProjectNo 去"红人合作跟踪"，前端
     * goToDetail() 在通用的"有值就跳需求管理"分支之前专门排除了这一类，不要在那边删掉这个
     * 特判，否则点这一类的"查看详情"会被误判成需求维度、跳错地方）。
     */
    @Column(name = "internal_requirement_no")
    private String internalRequirementNo;

    /**
     * 需求完成进度达到100%的那一刻（2026-08 新增，COLLAB_PAYMENT_DUE 需要invoice的分支专用）：
     * 这类品牌方的"结款周期"是从这个时间起算的，不是从"视频发布时间"起算，前端在"视频发布
     * 时间"列右边单独展示这个字段，方便核对"最迟结款日"到底是怎么算出来的。其余情况
     * （不需要invoice的记录、以及其它提醒类别）不设置，为 null。
     */
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "requirement_completed_at")
    private Date requirementCompletedAt;

    /**
     * 以下4个字段 2026-07 新增，供 PM_EXECUTOR_PROGRESS_STALL/FINANCE_PROGRESS_STALL 展示
     * 红人合作跟踪本身的关键信息用（红人视频制作与发布成本/结款周期/最迟结款日/视频发布时间
     * 这几个payment专属字段对这两类没有意义，已经不在前端展示，用这4个换成更贴切的信息）。
     */
    @Column(name = "platform", columnDefinition = "TEXT")
    private String platform;

    @Column(name = "video_type_label")
    private String videoTypeLabel;

    /**
     * 客户合作价格（美金）。语义按类别重新解释：PM_EXECUTOR_PROGRESS_STALL/
     * FINANCE_PROGRESS_STALL 是单条红人合作跟踪记录的客户合作价格；
     * REQUIREMENT_INVOICE_OVERDUE 是整个需求的客户合作总价格。
     */
    @Column(name = "client_price", precision = 15, scale = 2)
    private BigDecimal clientPrice;

    /** 内部执行人员姓名快照（PM_EXECUTOR_PROGRESS_STALL/FINANCE_PROGRESS_STALL 专用） */
    @Column(name = "executor_name")
    private String executorName;

    /**
     * 红人结款进度中文标签快照（2026-07 新增，COLLAB_PAYMENT_DUE 专用，供前端按"红人结款进度"
     * 筛选用）。这一类提醒已经过滤掉了"已纳入结款批次"的记录（见 runCollabPaymentDue），所以
     * 实际出现的值只会是 待红人发送invoice/红人已提供invoice/待结款（不涉及invoice）或 null
     * （还没设置）。其余类别不设置这个字段。
     */
    @Column(name = "payment_progress_label")
    private String paymentProgressLabel;

    /**
     * 提醒阈值天数快照（2026-07-29 新增，REQUIREMENT_INVOICE_OVERDUE/REQUIREMENT_CONTRACT_OVERDUE/
     * CONTRACT_EXPIRING_SOON 这3类专用）——这3类的 cycleDays 字段被复用成"需求条目总数"/没有
     * 意义，跑批当时实际用的阈值（可能已经被管理层在"进度提醒阈值维护"改过）之前是前端硬编码
     * 显示的固定文案（5天/14天/30天），阈值可维护之后那样会显示过期的旧数字，改成从这里读
     * 跑批当时真正用的值。其余类别不设置这个字段（PM_EXECUTOR_PROGRESS_STALL/
     * FINANCE_PROGRESS_STALL 复用 cycleDays 本身就是阈值，不需要这个字段）。
     */
    @Column(name = "threshold_days")
    private Integer thresholdDays;

    /**
     * 当前登录人是否已经"标记已处理"过这一行（2026-07 新增，不落库，只在
     * ProgressReminderService.listDetails() 返回前临时算好）。之前的做法是把标记已处理的行
     * 直接从返回列表里过滤掉，会导致主卡片标题的笔数（跑批时算的，不受标记影响）跟点进详情
     * 看到的笔数对不上，容易让人以为数据丢了；现在改成不过滤，保留这一行，只是标一下
     * acknowledged=true，前端据此把这一行变灰、操作列换成"已标记为已处理"文案。
     */
    @Transient
    private Boolean acknowledged;
}
