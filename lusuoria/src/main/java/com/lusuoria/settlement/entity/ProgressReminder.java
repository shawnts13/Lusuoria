package com.lusuoria.settlement.entity;

import com.lusuoria.settlement.enums.ReminderCategory;
import com.lusuoria.settlement.enums.ReminderUrgency;
import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 进度提醒（2026-07 新增）。
 *
 * 每天凌晨3点（北京时间）跑批一次，或由"管理层"在"待处理-进度提醒"页面手动点击
 * "结款后更新提示内容"触发：先清空这张表和 ProgressReminderDetail 里的全部旧数据，
 * 再把当次算出来的结果重新插入，所以这张表任何时刻都只保存"最新一次跑批"的结果，
 * 不会跨天累积（见 ProgressReminderService.runBatch()）。
 *
 * 一行代表"待处理-进度提醒"列表里的一条卡片：
 *   - category=COLLAB_PAYMENT_DUE：一个紧急程度档位一行（比如"0天或已超期：15笔"），
 *     具体命中的红人合作跟踪记录快照在 ProgressReminderDetail 里，通过 id 关联查询。
 *   - category=BRAND_MONTH_END_PAYMENT_DUE：一个"品牌方+结算月份"一行，本身就是一条完整消息，
 *     没有下钻明细。
 *
 * category 字段预留了扩展空间，以后有新类型的提醒跑批可以直接复用这张表，不需要改表结构
 * （跟 PendingApproval.category 是同样的设计思路）。
 */
@Entity
@Table(name = "progress_reminders")
@Getter
@Setter
public class ProgressReminder extends BaseEntity {

    /** 本次跑批的日期（北京时间），主要用于排查问题，展示层面不依赖这个字段 */
    @Temporal(TemporalType.DATE)
    @Column(name = "batch_date", nullable = false)
    private Date batchDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    private ReminderCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "urgency", nullable = false)
    private ReminderUrgency urgency;

    /**
     * 受众：员工角色（Employee.role），目前只有"管理层"这一个值。
     * 不是 SysUser.role——判断谁能看到这条提醒，是看登录账号关联的员工角色，
     * 跟登录账号本身是 ADMIN 还是 STAFF 无关（见 ProgressReminderService.isManagementUser）。
     */
    @Column(name = "audience_employee_role", nullable = false)
    private String audienceEmployeeRole;

    /** 预先算好的展示文案，前端直接展示（BRAND_MONTH_END_PAYMENT_DUE 就是完整消息本身） */
    @Column(name = "title", length = 500)
    private String title;

    /** COLLAB_PAYMENT_DUE 专用：命中的红人合作跟踪记录笔数 */
    @Column(name = "count")
    private Integer count;

    /** BRAND_MONTH_END_PAYMENT_DUE 专用：品牌方 id */
    @Column(name = "brand_id")
    private Long brandId;

    /** BRAND_MONTH_END_PAYMENT_DUE 专用：品牌方名称快照 */
    @Column(name = "brand_name")
    private String brandName;

    /** BRAND_MONTH_END_PAYMENT_DUE 专用：结算月份，格式 yyyyMM */
    @Column(name = "settlement_month", length = 6)
    private String settlementMonth;

    /** BRAND_MONTH_END_PAYMENT_DUE 专用：该品牌方该月"红人视频制作与发布成本"合计（美金） */
    @Column(name = "total_cost_amount", precision = 15, scale = 2)
    private BigDecimal totalCostAmount;

    /** BRAND_MONTH_END_PAYMENT_DUE 专用：最迟结款日（月底 + 付款周期配置的天数） */
    @Temporal(TemporalType.DATE)
    @Column(name = "deadline_date")
    private Date deadlineDate;

    /** BRAND_MONTH_END_PAYMENT_DUE 专用：距最迟结款日的剩余天数（负数/0表示已超期） */
    @Column(name = "days_remaining")
    private Integer daysRemaining;
}
