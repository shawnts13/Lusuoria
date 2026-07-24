package com.lusuoria.settlement.dto.response;

import lombok.Data;

/**
 * "需求完成进度"点击详情用：某条需求下已关联的红人合作跟踪记录简略信息
 * （项目视频类型/合作平台/需求内容/视频项目进度），"查看详情"跳转到红人合作跟踪对应记录。
 */
@Data
public class RequirementTrackingSummaryResponse {
    private Long trackingId;
    /** "查看详情"跳转用：红人合作跟踪列表页支持按内部项目编号精确筛选定位 */
    private String internalProjectNo;
    private String videoTypeLabel;
    /** 项目视频类型原始枚举值（前端按值上色用，videoTypeLabel 只是显示文案） */
    private String videoType;
    private String platform;
    private String demandContent;
    private String progressLabel;
    /** 视频项目进度的原始枚举值（前端按值上色用，progressLabel 只是显示文案） */
    private String progress;
    /**
     * 这条记录对应需求里的第几个条目（1-based，按条目在需求里的创建顺序编号），
     * 不落库、现算——按 (videoType, platform, 两个单价) 精确匹配到具体条目，跟
     * validateTrackingLinkage 用的是同一套匹配依据。理论上不会匹配不上，但万一遇到
     * 历史脏数据匹配不到时留 null，前端显示"—"。
     */
    private Integer itemIndex;
}
