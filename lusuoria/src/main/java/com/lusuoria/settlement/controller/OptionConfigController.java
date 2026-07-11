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

        // 红人合作跟踪 - 视频项目进度（原名"进度"）
        result.put("collab_progress", toOptions(
                new String[]{"PENDING_CLIENT_BRIEF", "CONTRACT_SENT", "INFLUENCER_ORDERED", "SHOOTING_GUIDE_SENT",
                             "PENDING_DRAFT", "PENDING_REVISION", "PENDING_PUBLISH",
                             "PUBLISHED_UNSETTLED", "JOINED_CLIENT_UNSETTLED_LIST", "SETTLED", "DELAYED"},
                new String[]{"待客户出brief", "合同已发给红人", "红人已下单", "拍摄指导已发给红人",
                             "待草稿", "待红人修改", "待发布",
                             "已发布（未结算）", "已加入客户未结算列表", "客户已结算", "折损"}));

        // 红人合作跟踪 - 红人结款进度。注意："已纳入红人结款批次"/"已纳入红人结款批次（缺少invoice）"
        // 这两个值只用于展示（列表页标签、状态流转弹窗禁用态回显），不应该出现在任何可选的下拉框里——
        // 前端状态流转/新建表单要自己把这两个值从下拉选项里过滤掉，见 CollaborationStatusModal/CollaborationFormModal
        result.put("influencer_payment_progress", toOptions(
                new String[]{"PENDING_INVOICE", "INVOICE_PROVIDED", "PENDING_SETTLEMENT_NO_INVOICE",
                             "INCLUDED_IN_PAYMENT_BATCH", "INCLUDED_IN_PAYMENT_BATCH_MISSING_INVOICE"},
                new String[]{"待红人发送invoice", "红人已提供invoice", "待结款（不涉及invoice）",
                             "已纳入红人结款批次", "已纳入红人结款批次（缺少invoice）"}));

        // 项目视频类型
        result.put("video_type", toOptions(
                new String[]{"REAL_SHOT_NEW", "REAL_SHOT_NEW_PHOTO", "AI_NEW_MATERIAL", "OLD_MATERIAL_REPOST"},
                new String[]{"实拍新视频", "实拍新图片", "AI新素材", "旧素材重发"}));

        // 员工角色
        result.put("employee_role", toOptions(
                new String[]{"项目负责人", "执行人员", "管理层", "财务", "法务", "IT后勤"},
                new String[]{"项目负责人", "执行人员", "管理层", "财务", "法务", "IT后勤"}));

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
