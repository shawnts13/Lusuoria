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

    private String sanitize(String input) {
        if (input == null) return "UNKNOWN";
        // 去掉空格和特殊字符，只保留字母数字
        return input.replaceAll("[^a-zA-Z0-9]", "").substring(0, Math.min(input.replaceAll("[^a-zA-Z0-9]", "").length(), 20));
    }
}