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
    private String platform;
    private String demandContent;
    private String progressLabel;
}
