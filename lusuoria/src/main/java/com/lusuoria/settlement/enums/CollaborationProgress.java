package com.lusuoria.settlement.enums;

/**
 * 红人合作跟踪 - 进度状态
 */
public enum CollaborationProgress {
    PENDING_DRAFT("待草稿"),
    PENDING_PUBLISH("待发布"),
    PENDING_REVISION("待修改"),
    PUBLISHED_UNSETTLED("已发布（未结算）"),
    JOINED_CLIENT_UNSETTLED_LIST("已加入客户未结算列表"),
    DELAYED("暂时延期"),
    SETTLED("已结算");

    private final String label;

    CollaborationProgress(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 根据中文标签反查枚举（Excel 导入用） */
    public static CollaborationProgress fromLabel(String label) {
        if (label == null) return null;
        String trimmed = label.trim();
        for (CollaborationProgress p : values()) {
            if (p.label.equals(trimmed)) return p;
        }
        return null;
    }
}
