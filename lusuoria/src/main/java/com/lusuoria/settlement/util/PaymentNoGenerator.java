package com.lusuoria.settlement.util;

import org.springframework.stereotype.Component;

/**
 * 结款单号生成器
 * 格式：品牌方-结算月份-序号
 * 示例：TEMU中国-202604-001
 *
 * 2026-07 起结款记录支持跨团队合并结算，单号里不再包含团队（不管这次结算涉及
 * 1个团队还是多个团队，格式都一样，不再区分）。
 *
 * 不复用 ProjectNoGenerator——那个会把中文和特殊字符剥掉转大写，
 * 结款单号要求原样保留中文品牌名。
 */
@Component
public class PaymentNoGenerator {

    public String generate(String brandName, String settlementMonth, long sequence) {
        return buildPrefix(brandName, settlementMonth) + String.format("%03d", sequence + 1);
    }

    /** 编号前缀（不含序号），用于统计"这个品牌+结算月份"下已经用了多少个编号 */
    public String buildPrefix(String brandName, String settlementMonth) {
        return brandName + "-" + settlementMonth + "-";
    }
}
