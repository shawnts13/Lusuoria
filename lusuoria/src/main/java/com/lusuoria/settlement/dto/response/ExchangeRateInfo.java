package com.lusuoria.settlement.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 数据看板 / 项目订单使用的汇率信息（人工维护）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeRateInfo {

    /** 业务月份，格式 202606 */
    private String yearMonth;

    /** 1 美元 = 多少人民币 */
    private BigDecimal usdToCny;

    /** true 表示该月份还没有管理员维护汇率，usdToCny 为 null */
    private Boolean isMissing;

    /** 最后修改人 */
    private String updatedBy;

    private Date lastUpdatedAt;
}
