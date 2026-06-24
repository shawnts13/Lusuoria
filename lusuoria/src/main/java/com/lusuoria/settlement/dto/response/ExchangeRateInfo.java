package com.lusuoria.settlement.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 数据看板右上角展示的汇率信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeRateInfo {

    /** 看板查看的月份，格式 202606 */
    private String yearMonth;

    /** 实际取数日期，格式 yyyy-MM-dd（上月最后一个工作日，或最近的交易日） */
    private String rateDate;

    /** 1 美元 = 多少人民币 */
    private BigDecimal usdToCny;

    /** 数据来源链接，前端点击后新开 tab 跳转 */
    private String sourceUrl;

    /** true 表示外部汇率接口请求失败，当前展示的是默认兜底汇率（非真实当日汇率） */
    private Boolean isFallback;
}
