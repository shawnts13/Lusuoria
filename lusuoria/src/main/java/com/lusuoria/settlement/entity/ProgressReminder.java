package com.lusuoria.settlement.entity;

import com.lusuoria.settlement.enums.OverdueUrgency;
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

    /**
     * 老两类（COLLAB_PAYMENT_DUE/BRAND_MONTH_END_PAYMENT_DUE，"临近提醒"方向，倒数天数）专用。
     * 2026-07 新增的3类"超期提醒"（方向相反，超出天数）改用下面的 overdueUrgency，
     * 这个字段对新类别没有实际展示意义，统一填一个占位值（不能留空，这一列是 NOT NULL 的
     * 历史列，ddl-auto 不会放松已有列的约束）。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "urgency", nullable = false)
    private ReminderUrgency urgency;

    /**
     * 2026-07 新增的3类"超期提醒"专用严重程度（1-3/4-7/8+工作日，黄/橙/红）。
     * 老两类不设置这个字段，颜色继续读 urgency。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "overdue_urgency")
    private OverdueUrgency overdueUrgency;

    /**
     * 受众：员工角色（Employee.role）。老两类只有"管理层"；2026-07 新增
     * FINANCE_PROGRESS_STALL 用"财务"；PM_EXECUTOR_PROGRESS_STALL/REQUIREMENT_INVOICE_OVERDUE
     * 按具体员工定向时，这里填该员工在这条记录里的身份（"项目负责人"/"执行人员"），实际可见性
     * 判断走下面的 audienceEmployeeId，这个字段只作展示用途。
     * 不是 SysUser.role——判断谁能看到这条提醒，是看登录账号关联的员工角色，
     * 跟登录账号本身是 ADMIN 还是 STAFF 无关（见 ProgressReminderService.isManagementUser）。
     */
    @Column(name = "audience_employee_role", nullable = false)
    private String audienceEmployeeRole;

    /**
     * PM_EXECUTOR_PROGRESS_STALL/REQUIREMENT_INVOICE_OVERDUE 专用：这条记录/需求名下这批
     * 红人合作跟踪的"主责人"——项目负责人。这两类现在统一只按项目负责人归类（2026-07
     * 起不再单独按执行人员归类，那样会导致同一条记录在管理层视角下被算两遍——见下面
     * involvedEmployeeIds 的注释）。老两类和 FINANCE_PROGRESS_STALL 留空，走角色级可见
     * （audienceEmployeeRole）。
     */
    @Column(name = "audience_employee_id")
    private Long audienceEmployeeId;

    /**
     * 2026-07 新增：这一批记录里涉及到的执行人员 id（换行分隔，MultiValueUtil 约定），
     * 只用于可见性判断——执行人员本人也能看到"项目负责人-XX-手下的"这条卡片（因为他们负责
     * 执行其中一部分），但卡片始终以项目负责人命名，不会单独再生成一条"执行人员"的卡片
     * （红人合作跟踪的主责人始终是项目负责人，执行人员不是"另一个主责人"）。查看详情时，
     * 执行人员看到的明细会按自己实际执行的那部分动态过滤，项目负责人/管理层看到全部。
     */
    @Column(name = "involved_employee_ids", columnDefinition = "TEXT")
    private String involvedEmployeeIds;

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
