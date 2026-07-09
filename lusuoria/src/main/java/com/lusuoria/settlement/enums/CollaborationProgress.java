package com.lusuoria.settlement.enums;

/**
 * 红人合作跟踪 - 视频项目进度（原名"进度"，字段本身/枚举 key 不变，只是显示名称改了）
 *
 * 枚举顺序即前端下拉框/Excel模板下拉框的展示顺序，对应真实业务流程先后：
 * 待客户出brief -> 合同已发给红人 -> 红人已下单 -> 拍摄指导已发给红人
 * -> 待草稿 -> 待红人修改 -> 待发布
 * -> 已发布（未结算） -> 已加入客户未结算列表 -> 客户已结算
 * -> 折损（流程外的异常终止状态，放在最后）
 */
public enum CollaborationProgress {
    PENDING_CLIENT_BRIEF("待客户出brief"),
    CONTRACT_SENT("合同已发给红人"),
    INFLUENCER_ORDERED("红人已下单"),
    SHOOTING_GUIDE_SENT("拍摄指导已发给红人"),
    PENDING_DRAFT("待草稿"),
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
