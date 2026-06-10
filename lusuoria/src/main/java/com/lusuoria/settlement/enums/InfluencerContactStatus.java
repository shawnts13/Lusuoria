package com.lusuoria.settlement.enums;

public enum InfluencerContactStatus {
    UNDEVELOPED("未开发"),
    REPLIED("已回复开发信"),
    INTERESTED("有合作意愿"),
    COOPERATING("正在合作"),
    COOPERATED("已合作过");

    private final String label;

    InfluencerContactStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
