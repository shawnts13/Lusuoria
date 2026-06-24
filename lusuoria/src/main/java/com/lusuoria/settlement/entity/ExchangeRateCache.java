package com.lusuoria.settlement.entity;

import lombok.*;

import javax.persistence.*;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 汇率缓存（按月缓存，避免每次看板请求都重新调用外部汇率API）
 *
 * 业务含义：数据看板查看某个月份的数据时，使用的是"上一个自然月最后一个工作日"的
 * USD/CNY 汇率。例如查看 202606 的数据，使用的是 202605 月最后一个工作日的汇率。
 *
 * yearMonth 字段存的是"被查看的月份"（如 202606），不是汇率发布的月份。
 */
@Entity
@Table(name = "exchange_rate_caches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExchangeRateCache extends BaseEntity {

    /** 看板查看的月份，格式 202606 */
    @Column(name = "year_month", nullable = false, unique = true, length = 6)
    private String yearMonth;

    /** 实际取数的日期（上月最后一个工作日，或 API 自动回退到的最近交易日），格式 yyyy-MM-dd */
    @Column(name = "rate_date", nullable = false, length = 10)
    private String rateDate;

    /** 1 美元 = 多少人民币 */
    @Column(name = "usd_to_cny", nullable = false, precision = 10, scale = 4)
    private BigDecimal usdToCny;

    /** 数据来源说明，用于前端展示链接 */
    @Column(name = "source_url", length = 255)
    private String sourceUrl;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "fetched_at")
    private Date fetchedAt;
}
