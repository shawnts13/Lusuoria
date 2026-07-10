package com.lusuoria.settlement.util;

import org.springframework.stereotype.Component;

/**
 * 结款单号生成器
 * 格式：品牌方[-红人团队]-结算月份-序号（团队为空时跳过团队这一段）
 * 示例：TEMU中国-田震团队-202604-001 / TEMU海外-202604-001
 *
 * 不复用 ProjectNoGenerator——那个会把中文和特殊字符剥掉转大写，
 * 结款单号要求原样保留中文品牌/团队名。
 */
@Component
public class PaymentNoGenerator {

    public String generate(String brandName, String teamName, String settlementMonth, long sequence) {
        return buildPrefix(brandName, teamName, settlementMonth) + String.format("%03d", sequence + 1);
    }

    /** 编号前缀（不含序号），用于统计"这个品牌+团队+结算月份"下已经用了多少个编号 */
    public String buildPrefix(String brandName, String teamName, String settlementMonth) {
        StringBuilder sb = new StringBuilder();
        sb.append(brandName);
        if (teamName != null && !teamName.trim().isEmpty()) {
            sb.append("-").append(teamName.trim());
        }
        sb.append("-").append(settlementMonth).append("-");
        return sb.toString();
    }
}
