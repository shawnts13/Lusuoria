package com.lusuoria.settlement.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 工资单明细弹窗里的一行维度数据。三种角色复用同一个结构，具体哪些字段有值取决于
 * PayslipDetailResponse.type：
 *  - PROJECT_MANAGER：brandName/teamName/videoCount/amount（客户合作价格）
 *  - EXECUTOR：brandName/teamName/videoType/videoTypeLabel/videoCount/amount（薪酬金额）
 *  - MANAGEMENT：brandName/teamName/videoCount/amount（客户合作价格）/amount2（红人成本）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayslipDimensionRow {
    private String brandName;
    private String teamName;
    private String videoType;
    private String videoTypeLabel;
    private Long videoCount;
    private BigDecimal amount;
    private BigDecimal amount2;
    /** 是否是汇总行（前端展示上加粗/置底） */
    private Boolean isSummaryRow;
}
