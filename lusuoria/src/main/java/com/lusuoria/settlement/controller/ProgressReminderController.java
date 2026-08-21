package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.entity.ProgressReminder;
import com.lusuoria.settlement.entity.ProgressReminderDetail;
import com.lusuoria.settlement.enums.ReminderCategory;
import com.lusuoria.settlement.service.impl.ProgressReminderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 进度提醒。目前唯一的受众是"管理层"（登录账号关联的员工角色，不是登录账号本身的角色），
 * 所有接口都自行按 ProgressReminderService.isCurrentUserManagement() 判定权限——
 * 不能用 @PreAuthorize("hasRole(...)") ，因为这不是按 SysUser.role 判定的。
 */
@RestController
@RequestMapping("/api/progress-reminders")
public class ProgressReminderController {

    @Autowired private ProgressReminderService progressReminderService;

    /** "待处理"页面顶部的进度提醒汇总卡片，按当前登录账号的职位角色自动过滤范围 */
    @GetMapping
    public ApiResponse<List<ProgressReminder>> list() {
        return ApiResponse.success(progressReminderService.listForCurrentUser());
    }

    /** 某一类提醒的明细列表（点开汇总卡片"查看详情"） */
    @GetMapping("/{id}/details")
    public ApiResponse<List<ProgressReminderDetail>> details(@PathVariable Long id) {
        return ApiResponse.success(progressReminderService.listDetails(id));
    }

    /** "结款后更新提示内容"：手动立即重新计算"临近结款"这两类，不用等到凌晨3点 */
    @PostMapping("/recompute")
    public ApiResponse<List<ProgressReminder>> recompute() {
        if (!progressReminderService.isCurrentUserManagement()) {
            return ApiResponse.error(403, "无权限执行此操作");
        }
        progressReminderService.runPaymentBatches();
        return ApiResponse.success(progressReminderService.listForCurrentUser());
    }

    /**
     * "项目流转后更新提示内容"（2026-07 新增，2026-08-21 改成异步）：这个操作是全表扫描，
     * 数据量越大越慢，之前同步执行、把这一个接口的前端超时单独调到120秒都还是会超时（见
     * ProgressReminderService 顶部"异步化"那段说明）。现在改成立刻返回"是否成功触发"，
     * 真正的计算交给后台线程，前端改成轮询 recompute-project-flow/status 判断有没有跑完，
     * 跑完后自己重新 GET 一次提醒列表刷新页面——不再像以前那样由这个接口直接把新列表带回来。
     */
    @PostMapping("/recompute-project-flow")
    public ApiResponse<Map<String, Object>> recomputeProjectFlow() {
        if (!progressReminderService.isCurrentUserManagement()) {
            return ApiResponse.error(403, "无权限执行此操作");
        }
        boolean started = progressReminderService.triggerProjectFlowRecompute();
        Map<String, Object> result = new HashMap<>();
        // started=false 表示已经有一次在后台跑，没有重复触发——不是失败，前端应该照样进入
        // "轮询等待"状态，而不是报错
        result.put("started", started);
        return ApiResponse.success(result);
    }

    /** 轮询"项目流转后更新提示内容"这次异步重算的进度（2026-08-21 新增），供按钮点击后前端定时调用 */
    @GetMapping("/recompute-project-flow/status")
    public ApiResponse<Map<String, Object>> recomputeProjectFlowStatus() {
        return ApiResponse.success(progressReminderService.getProjectFlowRecomputeStatus());
    }

    /** 登录/进入系统时调用：是否应该弹出"进度提醒"弹窗 */
    @GetMapping("/popup-check")
    public ApiResponse<Map<String, Object>> popupCheck() {
        Map<String, Object> result = new HashMap<>();
        result.put("shouldShow", progressReminderService.shouldShowPopup());
        return ApiResponse.success(result);
    }

    /** 用户点了弹窗上的按钮（跳转待处理/我知道了）后调用 */
    @PostMapping("/popup-dismiss")
    public ApiResponse<Void> popupDismiss() {
        progressReminderService.markPopupSeen();
        return ApiResponse.success();
    }

    /**
     * "标记已处理"（2026-07 新增，仅 PM_EXECUTOR_PROGRESS_STALL/FINANCE_PROGRESS_STALL/
     * REQUIREMENT_INVOICE_OVERDUE 这3类支持）：只影响标记人自己后续还看不看得到这条提醒。
     */
    @PostMapping("/acknowledge")
    public ApiResponse<Void> acknowledge(@RequestBody AcknowledgeRequest req) {
        progressReminderService.acknowledge(req.getCategory(), req.getTargetId());
        return ApiResponse.success();
    }

    /** 取消"标记已处理"（2026-07 新增）：防止误点，标记错了可以撤回 */
    @PostMapping("/unacknowledge")
    public ApiResponse<Void> unacknowledge(@RequestBody AcknowledgeRequest req) {
        progressReminderService.unacknowledge(req.getCategory(), req.getTargetId());
        return ApiResponse.success();
    }

    @lombok.Data
    public static class AcknowledgeRequest {
        private ReminderCategory category;
        private Long targetId;
    }
}
