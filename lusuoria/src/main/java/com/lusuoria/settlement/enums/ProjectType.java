package com.lusuoria.settlement.enums;

/**
 * 项目类型
 */
public enum ProjectType {
    OVERSEAS_INFLUENCER("海外红人"),
    CHINA_INFLUENCER("中国红人");

    private final String label;
    ProjectType(String label) { this.label = label; }
    public String getLabel() { return label; }
}