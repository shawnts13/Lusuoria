package com.lusuoria.settlement.enums;

public enum ProjectType {
    OVERSEAS_INFLUENCER("海外红人"),
    CHINA_INFLUENCER("中国红人"),
    FOREIGN_IN_CHINA("境外红人（在华）");

    private final String label;

    ProjectType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
