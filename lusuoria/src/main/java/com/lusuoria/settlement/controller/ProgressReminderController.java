package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.entity.ProgressReminder;
import com.lusuoria.settlement.entity.ProgressReminderDetail;
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

    @GetMapping
    public ApiResponse<List<ProgressReminder>> list() {
        return ApiResponse.success(progressReminderService.listForCurrentUser());
    }

    @GetMapping("/{id}/details")
    public ApiResponse<List<ProgressReminderDetail>> details(@PathVariable Long id) {
        return ApiResponse.success(progressReminderService.listDetails(id));
    }

    /** "结款后更新提示内容"：手动立即重新跑一次批，不用等到凌晨3点 */
    @PostMapping("/recompute")
    public ApiResponse<List<ProgressReminder>> recompute() {
        if (!progressReminderService.isCurrentUserManagement()) {
            return ApiResponse.error(403, "无权限执行此操作");
        }
        progressReminderService.runBatch();
        return ApiResponse.success(progressReminderService.listForCurrentUser());
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
}
