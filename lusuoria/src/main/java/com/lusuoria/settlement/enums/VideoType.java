package com.lusuoria.settlement.enums;

/**
 * 项目视频类型
 */
public enum VideoType {
    REAL_SHOT_NEW("实拍新视频"),
    AI_NEW_MATERIAL("AI新素材"),
    OLD_MATERIAL_REPOST("旧素材重发");

    private final String label;

    VideoType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 根据中文标签反查枚举（Excel 导入用） */
    public static VideoType fromLabel(String label) {
        if (label == null) return null;
        String trimmed = label.trim();
        for (VideoType v : values()) {
            if (v.label.equals(trimmed)) return v;
        }
        return null;
    }
}
