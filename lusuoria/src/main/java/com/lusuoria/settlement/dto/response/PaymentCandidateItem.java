package com.lusuoria.settlement.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 红人结款 - "选择涉及的红人视频项目"弹窗一行数据。
 * candidates 接口（可勾选的候选）和 {id}/items 接口（已纳入某条结款记录的明细）
 * 共用这个结构。
 */
@Data
public class PaymentCandidateItem {
    private Long trackingId;
    private String internalProjectNo;
    private String brandName;
    private String teamName;
    /** 团队原始 id（null 代表这条记录没有团队），前端用来在确认勾选后修正"红人团队"范围 */
    private Long teamId;
    private String accountName;
    /** 内部需求编号（可能为空，没关联"红人需求管理"需求的记录没有这个值） */
    private String internalRequirementNo;
    private String demandContent;
    private BigDecimal influencerCost;
    private String progressLabel;
    /** 视频项目进度的原始枚举值（前端按值上色用，progressLabel 只是显示文案） */
    private String progress;
    private String paymentProgressLabel;
    /** 红人结款进度的原始枚举值（前端按值上色用，paymentProgressLabel 只是显示文案） */
    private String paymentProgress;

    /** 视频发布时间 */
    private Date publishDate;

    /** 结款周期（天数），品牌方未配置付款周期规则时为 null */
    private Integer cycleDays;

    /** 最迟结款日，品牌方未配置付款周期规则时为 null */
    private Date deadlineDate;

    /** 上面这个 cycleDays/deadlineDate 是不是走的红人"特殊回款周期"（2026-08-21 新增，优先级
     *  最高，覆盖品牌方/团队级别配置）算出来的——供前端"选择涉及的红人视频项目"弹窗判断要不要
     *  展示"该结款记录涉及特殊回款周期的红人"这条红字提示 */
    private Boolean specialPaymentCycle;

    /** 红人结款进度 = 待红人发送invoice，前端红框+感叹号+提示文案用 */
    private boolean invoiceWarning;

    /** 品牌付款周期=月结 且已填对账日期时，视频发布时间落在对账日期所在月份，前端默认勾选用 */
    private boolean defaultChecked;

    // ===== 2026-08 新增：需要invoice的品牌方，"选择涉及的红人视频项目"按需求编号分组、
    // 且要求需求完成进度=100%才能勾选，需要这几个字段（没有关联需求的记录都为 null/0） =====

    /** 这条记录关联的需求当前已完成条目数（口径同"红人需求管理"列表页），没有关联需求时为 null */
    private Integer requirementCompletedCount;

    /** 这条记录关联的需求条目总数，没有关联需求时为 null */
    private Integer requirementTotalItemCount;

    /**
     * 这条记录关联的需求"实际可结款成本"之和（美金）：只加总 已发布(未结算)/已加入客户未结算
     * 列表/客户已结算 这三个终态的记录，不含"折损"——阈值分档结款/预计付款日用这个，不是
     * InfluencerRequirement.totalInfluencerCost（那是需求创建时按单价×数量算的计划总成本，
     * 没扣掉后续判"折损"、事实上不会付款的条目，2026-08 发现这个偏差后改用这个字段）。
     */
    private BigDecimal requirementPayableCost;

    /** 这条记录关联的需求达到"需求完成进度100%"的时间，阈值分档结款周期天数从这天开始算 */
    private Date requirementCompletedAt;

    /** 这条记录关联的需求下，"折损"状态的条目数（2026-08 新增，没有则为 0/null），前端提示用 */
    private Integer requirementDelayedCount;

    /** 这条记录关联的需求下，"折损"状态条目的红人视频制作与发布成本之和（美金），前端提示用 */
    private BigDecimal requirementDelayedCost;
}
