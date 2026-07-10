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

    /** 红人视频制作与发布成本（美金）快照 */
    @Column(name = "influencer_cost", precision = 15, scale = 2)
    private BigDecimal influencerCost;

    /** 视频项目进度中文标签快照（不存枚举，因为跑批之后这条记录的进度可能会变） */
    @Column(name = "progress_label")
    private String progressLabel;

    /** 视频发布时间快照 */
    @Temporal(TemporalType.DATE)
    @Column(name = "publish_date")
    private Date publishDate;

    /** 结款周期（天数），跑批时根据品牌方配置 + 单笔红人成本算出来的 */
    @Column(name = "cycle_days", nullable = false)
    private Integer cycleDays;

    /** 最迟结款日 = 视频发布时间 + 结款周期 */
    @Temporal(TemporalType.DATE)
    @Column(name = "deadline_date", nullable = false)
    private Date deadlineDate;
}
