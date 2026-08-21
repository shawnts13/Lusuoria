package com.lusuoria.settlement.enums;

/**
 * 红人合作跟踪 - 视频项目进度（原名"进度"，字段本身/枚举 key 不变，只是显示名称改了）
 *
 * 枚举顺序即前端下拉框/Excel模板下拉框的展示顺序，对应真实业务流程先后：
 * 待客户出brief -> 合同已发给红人 -> 待红人下单 -> 红人已下单 -> 拍摄指导已发给红人
 * -> 待草稿 -> 待客户给草稿反馈 -> 待红人修改 -> 待发布
 * -> 已发布（未结算） -> 已加入客户未结算列表 -> 客户已结算
 * -> 折损（流程外的异常终止状态，放在最后）
 *
 * 2026-08-17 新增"待红人下单"（PENDING_INFLUENCER_ORDER），插在"合同已发给红人"和"红人已下单"
 * 之间——合同发给红人之后、红人真正下单之前，之前这段空档没有单独的状态覆盖。新增这个值只是
 * 在中间插入一个 enum 常量，ordinal() 之后的所有值会整体后移，但这个类/代码库里目前没有任何
 * 地方依赖写死的 ordinal 数字做比较（进度倒退检测走的是 allowsPaymentProgress() 这个语义
 * 方法，不是序号），所以是安全的。滞留提醒阈值单独一档（4个工作日，可在"进度提醒阈值维护"
 * 调），见 ProgressReminderService.stallThreshold()。
 *
 * 2026-08-21 新增"待客户给草稿反馈"（PENDING_CLIENT_DRAFT_FEEDBACK），插在"待草稿"和"待红人
 * 修改"之间——草稿做完发给客户之后、客户反馈意见之前，之前这段空档也没有单独的状态覆盖，跟上面
 * "待红人下单"是同一类插入。这次滞留提醒阈值不单独开一档，直接复用"其余进度状态滞留阈值"
 * （STALL_THRESHOLD_MID，见 ProgressReminderService.PM_EXECUTOR_3DAY_STATES），因为 Shawn
 * 明确要求共用，不需要新增阈值参数。
 */
public enum CollaborationProgress {
    PENDING_CLIENT_BRIEF("待客户出brief"),
    CONTRACT_SENT("合同已发给红人"),
    PENDING_INFLUENCER_ORDER("待红人下单"),
    INFLUENCER_ORDERED("红人已下单"),
    SHOOTING_GUIDE_SENT("拍摄指导已发给红人"),
    PENDING_DRAFT("待草稿"),
    PENDING_CLIENT_DRAFT_FEEDBACK("待客户给草稿反馈"),   // 2026-08-21 新增，插在"待草稿"和"待红人修改"之间
    PENDING_REVISION("待红人修改"),          // 枚举 key 不变，仅显示名称由"待修改"改为"待红人修改"
    PENDING_PUBLISH("待发布"),
    PUBLISHED_UNSETTLED("已发布（未结算）"),
    JOINED_CLIENT_UNSETTLED_LIST("已加入客户未结算列表"),
    SETTLED("客户已结算"),                    // 枚举 key 不变，仅显示名称由"已结算"改为"客户已结算"
    DELAYED("折损");                          // 枚举 key 不变，仅显示名称由"暂时延期"改为"折损"

    private final String label;

    CollaborationProgress(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 是否达到"红人结款进度"字段的前置要求（只有这三个阶段才允许设置红人结款进度）。
     */
    public boolean allowsPaymentProgress() {
        return this == PUBLISHED_UNSETTLED || this == JOINED_CLIENT_UNSETTLED_LIST || this == SETTLED;
    }

    /** 根据中文标签反查枚举（Excel 导入用），同时兼容改名前的旧标签文本 */
    public static CollaborationProgress fromLabel(String label) {
        if (label == null) return null;
        String trimmed = label.trim();
        for (CollaborationProgress p : values()) {
            if (p.label.equals(trimmed)) return p;
        }
        // 兼容改名前的旧标签文本：历史导出的 Excel 文件、用户手头存量的老模板
        // 里可能还是旧名字，不能因为改名就让老文件导入失败
        switch (trimmed) {
            case "已结算": return SETTLED;
            case "暂时延期": return DELAYED;
            case "待修改": return PENDING_REVISION;
            default: return null;
        }
    }
}
