package com.lusuoria.settlement.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 进度提醒明细行（2026-07 新增），仅 category=COLLAB_PAYMENT_DUE 的 ProgressReminder 才有明细。
 *
 * 每行对应一条命中的红人合作跟踪记录，在跑批当时把展示要用的字段整体快照下来（而不是
 * 存一个 trackingId 现查现拼），这样当天不管看多少次"待处理-进度提醒"或者弹窗，看到的
 * 数字和明细列表都严格对得上，不会因为白天有人改了红人合作跟踪的数据而对不上。
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
     * 是整个需求的红人视频制作与发布总成本。
     */
    @Column(name = "influencer_cost", precision = 15, scale = 2)
    private BigDecimal influencerCost;

    /** 视频项目进度中文标签快照（不存枚举，因为跑批之后这条记录的进度可能会变） */
    @Column(name = "progress_label")
    private String progressLabel;

    /** 视频发布时间快照 */
    @Temporal(TemporalType.DATE)
    @Column(name = "publish_date")
    private Date publishDate;

    /**
     * 历史 NOT NULL 列，含义按类别重新解释（2026-07）：
     *   - COLLAB_PAYMENT_DUE：结款周期（天数），根据品牌方配置 + 单笔红人成本算出来的。
     *   - PM_EXECUTOR_PROGRESS_STALL/FINANCE_PROGRESS_STALL："提醒阈值（工作日）"——
     *     超过多少个工作日没流转就算滞留（3/5/14）。
     *   - REQUIREMENT_INVOICE_OVERDUE："需求条目总数"（不是天数）。
     */
    @Column(name = "cycle_days", nullable = false)
    private Integer cycleDays;

    /** 最迟结款日 = 视频发布时间 + 结款周期 */
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
     * 不用 trackingId）。
     */
    @Column(name = "requirement_id")
    private Long requirementId;

    @Column(name = "internal_requirement_no")
    private String internalRequirementNo;

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
}
