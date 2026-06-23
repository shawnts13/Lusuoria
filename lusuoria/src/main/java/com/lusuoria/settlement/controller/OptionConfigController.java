package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.config.InfluencerOptions;
import com.lusuoria.settlement.dto.response.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 下拉选项配置接口
 * 所有选项数据来自 InfluencerOptions 常量类，改选项只需改那一处。
 * 前端调用一次后缓存4小时。
 */
@RestController
@RequestMapping("/api/config")
public class OptionConfigController {

    @GetMapping("/options/all")
    public ApiResponse<Map<String, List<Map<String, String>>>> getAllOptions() {
        Map<String, List<Map<String, String>>> result = new LinkedHashMap<String, List<Map<String, String>>>();

        result.put("influencer_type", toOptions(
                new String[]{"OVERSEAS_INFLUENCER", "CHINA_INFLUENCER", "FOREIGN_IN_CHINA"},
                InfluencerOptions.INFLUENCER_TYPES));

        result.put("contact_status", toOptions(
                new String[]{"UNDEVELOPED", "REPLIED", "INTERESTED", "COOPERATING", "COOPERATED"},
                InfluencerOptions.CONTACT_STATUSES));

        result.put("payment_cycle", toOptions(
                InfluencerOptions.PAYMENT_CYCLES,
                InfluencerOptions.PAYMENT_CYCLES));

        result.put("term", toOptions(
                InfluencerOptions.TERMS,
                InfluencerOptions.TERMS));

        // 注意：领域已改为数据库表管理，通过 /api/domains 接口获取

        result.put("platform", toOptions(
                InfluencerOptions.PLATFORMS,
                InfluencerOptions.PLATFORMS));

        result.put("country", toOptions(
                InfluencerOptions.COUNTRIES,
                InfluencerOptions.COUNTRIES));

        return ApiResponse.success(result);
    }

    /** value 和 label 分开传（枚举 key 和中文 label 不同的情况） */
    private List<Map<String, String>> toOptions(String[] values, String[] labels) {
        List<Map<String, String>> list = new ArrayList<Map<String, String>>();
        for (int i = 0; i < values.length; i++) {
            Map<String, String> m = new LinkedHashMap<String, String>();
            m.put("value", values[i]);
            m.put("label", labels[i]);
            list.add(m);
        }
        return list;
    }
}
