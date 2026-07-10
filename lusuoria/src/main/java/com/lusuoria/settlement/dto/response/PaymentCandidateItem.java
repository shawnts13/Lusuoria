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
    private String accountName;
    private String demandContent;
    private BigDecimal influencerCost;
    private String progressLabel;
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
}
