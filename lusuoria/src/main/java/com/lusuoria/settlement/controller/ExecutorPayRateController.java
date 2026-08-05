package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.entity.ExecutorPayRateTier;
import com.lusuoria.settlement.enums.VideoType;
import com.lusuoria.settlement.repository.ExecutorPayRateTierRepository;
import com.lusuoria.settlement.util.EmployeeRoleUtil;
import com.lusuoria.settlement.util.RoleUtil;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 执行人员薪资梯度 - 按 (项目负责人, 执行人员, 项目视频类型) 独立维护，每个视频类型都是一份
 * "按当月累计条数分档"的梯度价（2026-07 起四个视频类型统一走这套结构，"每条固定价"只是
 * 恰好只维护了一档、且不封顶）。
 *
 * 权限收窄规则（2026-07 新增，跟"员工管理"/"执行人员管理"两个前端模块的拆分对应）：
 *  - STAFF 账号（无论员工角色是"项目负责人"还是"管理层"）：managerId 一律强制用登录账号
 *    自己关联的员工 id 覆盖请求里传的值，看不到、也改不了别人配的价；员工角色不是这两者之一
 *    的 STAFF 账号完全拒绝访问。
 *  - ADMIN：可以显式指定 managerId（"员工管理"页面配置某个执行人员的费率时，代表系统里
 *    唯一的"管理层"员工传过去），不传时默认取 {@link EmployeeCache#findManagementEmployee()}。
 */
@RestController
@RequestMapping("/api/executor-pay-rates")
public class ExecutorPayRateController {

    @Autowired private ExecutorPayRateTierRepository tierRepo;
    @Autowired private EmployeeRoleUtil employeeRoleUtil;
    @Autowired private EmployeeCache employeeCache;

    /** 解析当前调用方实际生效的 managerId：STAFF 强制用自己的员工 id，ADMIN 可显式指定 */
    private Long resolveManagerId(Long requestedManagerId) {
        if (RoleUtil.isAdmin()) {
            if (requestedManagerId != null) return requestedManagerId;
            Employee management = employeeCache.findManagementEmployee();
            if (management == null) throw new RuntimeException("系统里还没有配置角色为\"管理层\"的员工");
            return management.getId();
        }
        String empRole = employeeRoleUtil.getCurrentEmployeeRole();
        if (!"项目负责人".equals(empRole) && !"管理层".equals(empRole)) {
            throw new RuntimeException("无权限维护执行人员薪资梯度");
        }
        Long ownEmployeeId = employeeRoleUtil.getCurrentEmployeeId();
        if (ownEmployeeId == null) throw new RuntimeException("当前账号未关联员工记录");
        return ownEmployeeId;
    }

    /** 某个负责人名下配置的所有执行人员费率梯度，前端按 executorId 再按 videoType 分组展示 */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<List<ExecutorPayRateTier>> list(@RequestParam(required = false) Long managerId) {
        Long effectiveManagerId = resolveManagerId(managerId);
        return ApiResponse.success(tierRepo.findByManagerIdAndIsDeletedFalseOrderByMinCountAsc(effectiveManagerId));
    }

    /**
     * 轻量存在性检查：供"红人合作跟踪"编辑表单在触发原子保存前判断这个 (负责人,执行人员,
     * 视频类型) 是否已配置费率梯度——2026-07 起配置是按视频类型分开的，同一个执行人员可能
     * 配了"实拍新视频"但没配"AI新素材"，所以必须精确到视频类型才能判断。
     */
    @GetMapping("/check")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF','AUDITOR')")
    public ApiResponse<Boolean> check(@RequestParam Long managerId, @RequestParam Long executorId,
                                       @RequestParam VideoType videoType) {
        boolean exists = !tierRepo.findByManagerIdAndExecutorIdAndVideoTypeAndIsDeletedFalseOrderByMinCountAsc(
                managerId, executorId, videoType).isEmpty();
        return ApiResponse.success(exists);
    }

    /**
     * 整批替换某个 (负责人,执行人员) 名下全部视频类型的费率梯度（先删后插）。
     * 请求体按视频类型分组传全部档位，某个视频类型没传或传空列表就表示"这个类型不配置"。
     */
    /**
     * 整批替换需要先删后插，deleteByManagerIdAndExecutorId 这类派生删除方法必须在事务里执行
     * 才能真正 remove 掉查到的记录，不然会报 "No EntityManager with actual transaction available
     * for current thread" —— 没删过东西的时候（比如第一次保存）不会触发这个报错，直到真的有
     * 记录要删才会暴露出来，所以务必显式声明 @Transactional。
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    @Transactional
    public ApiResponse<Void> save(@RequestBody SaveRequest req) {
        Long effectiveManagerId = resolveManagerId(req.getManagerId());
        if (req.getExecutorId() == null) throw new RuntimeException("请选择执行人员");

        tierRepo.deleteByManagerIdAndExecutorId(effectiveManagerId, req.getExecutorId());

        if (req.getTiersByType() != null) {
            for (Map.Entry<VideoType, List<TierItem>> entry : req.getTiersByType().entrySet()) {
                VideoType videoType = entry.getKey();
                List<TierItem> tiers = entry.getValue();
                if (tiers == null || tiers.isEmpty()) continue;

                // 必填字段（最低条数/单价）先校验完，再排序——下面排序用的 comparator 会直接对
                // minCount 调用 compareTo()，缺了这个字段会先 NullPointerException，走不到
                // 后面这些更友好的报错文案，报出一个用户看不懂的 500（2026-08 修复，同时前端
                // ExecutorRateFields.vue 也不再允许把不完整的档位悄悄过滤掉再提交，这里是后端兜底）
                for (TierItem item : tiers) {
                    if (item.getMinCount() == null || item.getMinCount() < 1) {
                        throw new RuntimeException(videoType.getLabel() + "：档位最低条数必须≥1");
                    }
                    if (item.getRate() == null) {
                        throw new RuntimeException(videoType.getLabel() + "：档位单价不能为空");
                    }
                }

                List<TierItem> sorted = tiers.stream()
                        .sorted((a, b) -> a.getMinCount().compareTo(b.getMinCount()))
                        .collect(Collectors.toList());
                for (int i = 0; i < sorted.size(); i++) {
                    TierItem item = sorted.get(i);
                    boolean isLast = i == sorted.size() - 1;
                    if (item.getMaxCount() != null && item.getMaxCount() < item.getMinCount()) {
                        throw new RuntimeException(videoType.getLabel() + "：档位最高条数不能小于最低条数");
                    }
                    // 只有排在最后的那一档才允许不封顶数量（maxCount 为空），且只有这一档才允许设置当月封顶金额
                    if (item.getMaxCount() == null && !isLast) {
                        throw new RuntimeException(videoType.getLabel() + "：只有排在最后的档位才能不设置最高条数");
                    }
                    if (item.getMonthlyCap() != null && item.getMaxCount() != null) {
                        throw new RuntimeException(videoType.getLabel() + "：当月封顶金额只能设置在不封顶条数的那一档上");
                    }
                    tierRepo.save(ExecutorPayRateTier.builder()
                            .managerId(effectiveManagerId)
                            .executorId(req.getExecutorId())
                            .videoType(videoType)
                            .minCount(item.getMinCount())
                            .maxCount(item.getMaxCount())
                            .rate(item.getRate())
                            .monthlyCap(item.getMonthlyCap())
                            .build());
                }
            }
        }
        return ApiResponse.success();
    }

    @Data
    public static class SaveRequest {
        /** ADMIN 可显式指定；STAFF 传了也会被后端忽略，强制用自己的员工 id */
        private Long managerId;
        private Long executorId;
        private Map<VideoType, List<TierItem>> tiersByType;
    }

    @Data
    public static class TierItem {
        private Integer minCount;
        private Integer maxCount;
        private BigDecimal rate;
        private BigDecimal monthlyCap;
    }
}
