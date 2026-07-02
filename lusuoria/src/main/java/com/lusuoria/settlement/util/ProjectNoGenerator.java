package com.lusuoria.settlement.util;

import org.springframework.stereotype.Component;

/**
 * 项目编号生成器
 * 格式：品牌方-月份-红人账号-序号
 * 示例：TEMU-202604-bigdogtech-001
 */
@Component
public class ProjectNoGenerator {

    public String generate(String brandName, String projectMonth, String accountName, long sequence) {
        String brand   = sanitize(brandName).toUpperCase();
        String account = sanitize(accountName).toLowerCase();
        String seq     = String.format("%03d", sequence + 1);
        return brand + "-" + projectMonth + "-" + account + "-" + seq;
    }

    /** 编号前缀（不含序号），用于统计"这个品牌+月份+账号"下已经用了多少个编号 */
    public String buildPrefix(String brandName, String projectMonth, String accountName) {
        String brand   = sanitize(brandName).toUpperCase();
        String account = sanitize(accountName).toLowerCase();
        return brand + "-" + projectMonth + "-" + account + "-";
    }

    private String sanitize(String input) {
        if (input == null) return "UNKNOWN";
        // 去掉空格和特殊字符，只保留字母数字
        return input.replaceAll("[^a-zA-Z0-9]", "").substring(0, Math.min(input.replaceAll("[^a-zA-Z0-9]", "").length(), 20));
    }
}