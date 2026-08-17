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

    /** 生成完整项目编号，sequence 是"这个前缀下已有几个"（调用方查好传进来），编号从 sequence+1 开始 */
    public String generate(String brandName, String teamName, String projectMonth, String accountName, long sequence) {
        return buildPrefix(brandName, teamName, projectMonth, accountName) + String.format("%03d", sequence + 1);
    }

    /** 编号前缀（不含序号），用于统计"这个品牌+团队+月份+账号"下已经用了多少个编号 */
    public String buildPrefix(String brandName, String teamName, String projectMonth, String accountName) {
        // 2026-08-15 补的防御性检查：调用方（CollaborationTrackingService.doSave()）现在已经
        // 强制要求品牌方非空才会走到这里，正常情况下 brandName 不会是 null；这里额外拦一道，
        // 是因为 StringBuilder.append(null) 会老老实实拼出字面量"null"这4个字符而不是报错——
        // 之前就是这样悄悄生成过"null-202608-xxx-001"这种脏内部项目编号的，早发现比晚发现好，
        // 宁可这里直接抛异常暴露问题，也不要让调用方的疏漏继续变成静默的脏数据
        if (brandName == null || brandName.trim().isEmpty()) {
            throw new IllegalArgumentException("生成内部项目编号时品牌方不能为空");
        }
        String account = sanitizeAccount(accountName);
        StringBuilder sb = new StringBuilder();
        sb.append(brandName);
        if (teamName != null && !teamName.trim().isEmpty()) {
            sb.append("-").append(teamName.trim());
        }
        sb.append("-").append(projectMonth).append("-").append(account).append("-");
        return sb.toString();
    }

    /** 红人账号名转成编号里能用的形式：去空格/特殊字符、转小写、最长截到20位；空值兜底成 "unknown" */
    private String sanitizeAccount(String input) {
        if (input == null) return "unknown";
        // 去掉空格和特殊字符，只保留字母数字
        String cleaned = input.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        return cleaned.substring(0, Math.min(cleaned.length(), 20));
    }
}
