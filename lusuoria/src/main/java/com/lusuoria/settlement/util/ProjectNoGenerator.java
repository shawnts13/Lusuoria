package com.lusuoria.settlement.util;

import org.springframework.stereotype.Component;

/**
 * 项目编号生成器
 * 格式：品牌方-红人团队-月份-红人账号-序号（没有团队时省略团队这一段）
 * 示例：TEMU中国-田震团队-202604-bigdogtech-001 / TEMU海外-202604-bigdogtech-001
 *
 * 2026-07 起品牌方/团队原样保留（不再 ASCII 化、不再转大写），风格上跟结款单号
 * PaymentNoGenerator 保持一致；红人账号本身基本都是英文 handle，沿用原来的
 * 小写+去特殊字符处理。
 */
@Component
public class ProjectNoGenerator {

    public String generate(String brandName, String teamName, String projectMonth, String accountName, long sequence) {
        return buildPrefix(brandName, teamName, projectMonth, accountName) + String.format("%03d", sequence + 1);
    }

    /** 编号前缀（不含序号），用于统计"这个品牌+团队+月份+账号"下已经用了多少个编号 */
    public String buildPrefix(String brandName, String teamName, String projectMonth, String accountName) {
        String account = sanitizeAccount(accountName);
        StringBuilder sb = new StringBuilder();
        sb.append(brandName);
        if (teamName != null && !teamName.trim().isEmpty()) {
            sb.append("-").append(teamName.trim());
        }
        sb.append("-").append(projectMonth).append("-").append(account).append("-");
        return sb.toString();
    }

    private String sanitizeAccount(String input) {
        if (input == null) return "unknown";
        // 去掉空格和特殊字符，只保留字母数字
        String cleaned = input.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        return cleaned.substring(0, Math.min(cleaned.length(), 20));
    }
}
