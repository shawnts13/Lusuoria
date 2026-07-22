package com.lusuoria.settlement.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 多值字段（换行/逗号分隔存成一个字符串列，如红人库"服务国家/市场"、"平台"、"所属领域"）
 * 的拆分/拼接工具，供各处需要"单个自动填/多个手动选一个"这类规则的地方复用。
 */
public class MultiValueUtil {

    private MultiValueUtil() {}

    /** 换行/逗号分隔的多值字段拆成列表，过滤空白项 */
    public static List<String> splitMulti(String s) {
        if (s == null || s.trim().isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (String part : s.split("[\\n,]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    /**
     * "单个自动填/多个手动选"规则的公共实现：选项只有 0/1 个时忽略请求值直接采用
     * （0 个时结果为 null）；有多个选项时请求值必须是其中之一，否则抛出异常。
     *
     * @param fieldLabel 报错文案里用的字段名，如"服务国家/市场"
     */
    public static String resolveSingleChoice(String optionsRaw, String requestedValue, String fieldLabel) {
        List<String> options = splitMulti(optionsRaw);
        if (options.size() <= 1) {
            return options.isEmpty() ? null : options.get(0);
        }
        if (requestedValue != null && options.contains(requestedValue)) {
            return requestedValue;
        }
        throw new RuntimeException("该红人维护了多个" + fieldLabel + "，请明确选择其中一个");
    }
}
