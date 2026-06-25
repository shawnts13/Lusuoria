package com.lusuoria.settlement.entity;

import lombok.*;

import javax.persistence.*;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 月度汇率（人工维护，仅管理员可填写）
 *
 * 业务含义：数据看板及项目订单查看/生成某个月份的数据时，使用该月份对应的
 * USD/CNY 汇率。汇率不再自动抓取，完全由管理员手动维护（中国法定节假日和
 * 调休规则复杂多变，自动化判断"工作日"风险较高，改为人工对照中国银行官网
 * 填写更可靠）。
 *
 * yearMonth 字段存的是"业务月份"（如 202606），即项目订单/看板实际使用此汇率的月份。
 *
 * 修改某月汇率后，该月所有已存在的项目订单会被强制覆盖为新汇率。
 */
@Entity
@Table(name = "exchange_rate_caches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExchangeRateCache extends BaseEntity {

    /** 业务月份，格式 202606 */
    @Column(name = "year_month", nullable = false, unique = true, length = 6)
    private String yearMonth;

    /** 1 美元 = 多少人民币（管理员手动填写） */
    @Column(name = "usd_to_cny", nullable = false, precision = 10, scale = 4)
    private BigDecimal usdToCny;

    /** 最后一次修改人（员工/用户名，便于追溯） */
    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "last_updated_at")
    private Date lastUpdatedAt;
}
