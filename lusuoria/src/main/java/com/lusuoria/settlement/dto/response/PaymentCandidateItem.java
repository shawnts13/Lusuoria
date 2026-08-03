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

    /** 这条记录关联的需求"红人视频制作与发布总成本"（美金），阈值分档结款用这个而不是单笔成本 */
    private BigDecimal requirementTotalInfluencerCost;

    /** 这条记录关联的需求达到"需求完成进度100%"的时间，阈值分档结款周期天数从这天开始算 */
    private Date requirementCompletedAt;
}
