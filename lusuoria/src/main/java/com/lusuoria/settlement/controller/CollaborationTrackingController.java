package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.dto.request.CollaborationTrackingRequest;
import com.lusuoria.settlement.dto.request.CollaborationTrackingStatusRequest;
import com.lusuoria.settlement.dto.request.DeleteRequestReasonRequest;
import com.lusuoria.settlement.dto.request.MarkPaymentReceivedRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.dto.response.CollaborationStatusUpdateResult;
import com.lusuoria.settlement.dto.response.ExecutorCostSuggestionResponse;
import com.lusuoria.settlement.dto.response.ExecutorCostUpdateResult;
import com.lusuoria.settlement.dto.response.PaymentReceivedPreviewResponse;
import com.lusuoria.settlement.entity.CollaborationTracking;
import com.lusuoria.settlement.entity.ImportBatch;
import com.lusuoria.settlement.entity.PendingApproval;
import com.lusuoria.settlement.enums.CollaborationProgress;
import com.lusuoria.settlement.enums.InfluencerPaymentProgress;
import com.lusuoria.settlement.enums.PendingApprovalCategory;
import com.lusuoria.settlement.enums.PendingApprovalModule;
import com.lusuoria.settlement.enums.VideoType;
import com.lusuoria.settlement.excel.CollaborationTrackingExcelHandler;
import com.lusuoria.settlement.repository.CollaborationTrackingRepository;
import com.lusuoria.settlement.repository.ImportBatchRepository;
import com.lusuoria.settlement.repository.PendingApprovalRepository;
import com.lusuoria.settlement.service.impl.CollaborationTrackingService;
import com.lusuoria.settlement.util.CollaborationFilterUtil;
import com.lusuoria.settlement.util.EmployeeRoleUtil;
import com.lusuoria.settlement.util.ProjectFieldVisibility;
import com.lusuoria.settlement.util.RoleUtil;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.JpaSort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 红人合作跟踪
 *
 * 特殊响应码：
 *   4091 - 去重命中（前端提示，不重试）
 */
@RestController
@RequestMapping("/api/collaboration-trackings")
public class CollaborationTrackingController {

    public static final int CODE_DUPLICATE           = 4091;

    @Autowired private CollaborationTrackingRepository trackingRepo;
    @Autowired private CollaborationTrackingService trackingService;
    @Autowired private CollaborationTrackingExcelHandler excelHandler;
    @Autowired private ImportBatchRepository importBatchRepo;
    @Autowired private BrandCache brandCache;
    @Autowired private PendingApprovalRepository pendingApprovalRepo;
    @Autowired private ProjectFieldVisibility fieldVisibility;
    @Autowired private EmployeeRoleUtil employeeRoleUtil;

    /**
     * 红人合作跟踪列表页（CollaborationListPage.vue）的唯一数据接口——筛选栏、4 个互斥"快速筛选"
     * 开关、列头排序、分页，都是这一个方法。参数分组对应前端：
     *   - brandId ~ projectManagerId：筛选栏里的各个下拉/输入框，一一对应，全部可选、AND 组合。
     *     其中 progress/influencerPaymentProgress/videoType/projectManagerId 这4个（2026-08-21
     *     起）改成 List 类型，支持前端多选——单选时前端仍然只传一个值，Spring 会自动装进
     *     单元素 List，行为不变；传多个值时 WHERE 子句用 IN 而不是 =，同一个筛选项内部是 OR
     *     语义（比如"视频项目进度"选了 A、B，就是查 A 或 B），不同筛选项之间仍然是 AND
     *   - onlyMyResponsibility：本来是"只看我负责的"筛选，现在主要是配合 sortBy=id 时的默认排序
     *     用（见下面 resolvePriorityEmployeeId），前端筛选栏目前没有单独暴露这个开关
     *   - onlyIncomplete / onlyUnpublished / onlyMissingRequirementNo：列表页顶部"查看未完成的需求" /
     *     "查看视频未发布的记录" / "查看未绑定需求编号的记录"这 3 个互斥快速筛选按钮（第 4 个"查看
     *     未上传invoice/合同的需求"按钮在红人需求管理模块，不是这个接口）
     *   - sortBy/sortDir：点列头排序；sortBy=id（默认，未点任何列头）时不是简单按 id 排，而是走
     *     buildIncompleteAndUnbatchedFirstPage 那一套"未完成/未结款优先"的桶排序，见下方说明
     *   - page/size：分页器
     */
    @GetMapping
    public ApiResponse<Page<CollaborationTracking>> list(
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String countryMarket,
            @RequestParam(required = false) String accountName,
            @RequestParam(required = false) Long influencerId,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) List<CollaborationProgress> progress,
            @RequestParam(required = false) List<InfluencerPaymentProgress> influencerPaymentProgress,
            @RequestParam(required = false) List<VideoType> videoType,
            @RequestParam(required = false) String videoMonth,
            @RequestParam(required = false) String videoDateStart,
            @RequestParam(required = false) String videoDateEnd,
            @RequestParam(required = false) String internalProjectNo,
            @RequestParam(required = false) String internalRequirementNo,
            @RequestParam(required = false) String clientOrderId,
            @RequestParam(required = false) String clientPaymentBatch,
            @RequestParam(required = false) List<Long> projectManagerId,
            @RequestParam(defaultValue = "false") boolean onlyMyResponsibility,
            @RequestParam(defaultValue = "false") boolean onlyIncomplete,
            @RequestParam(defaultValue = "false") boolean onlyUnpublished,
            @RequestParam(defaultValue = "false") boolean onlyMissingRequirementNo,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        size = Math.max(1, Math.min(size, 200));
        // accountName 是关联的红人记录上的字段，不是本表自己的属性，
        // 排序时要用 JPQL 的关联路径写法（influencer.accountName），不能直接用列名
        String sortProperty = "accountName".equals(sortBy) ? "influencer.accountName" : sortBy;
        Sort.Direction userSortDirection = sortDir.equalsIgnoreCase("asc") ? Sort.Direction.ASC : Sort.Direction.DESC;
        // 下面这一段（到 pageable 为止）不对应某个具体按钮，是列表默认排序背后看不见的逻辑：
        // 登录人是项目负责人/执行人员/管理层时，自己名下的记录自动排到最前面；登录人是财务时，
        // 进入结算区间的记录排到最前面。用户点了列头排序（accountName/videoDate 等）时，这两级
        // "优先展示"仍然生效，只是同一优先级内部再按用户选的列排序（见最后 .and(Sort.by(...))）。
        Long priorityEmployeeId = resolvePriorityEmployeeId();
        boolean prioritizeFinance = resolvePrioritizeFinance();
        // "优先展示"的两级 CASE 排序不能写死在 @Query 的 ORDER BY 里，会触发 Spring Data 2.7.x
        // 对复杂括号嵌套查询的 ORDER BY 检测 bug（详见 CollaborationTrackingRepository.findByFilters
        // 上的注释），所以改成用 JpaSort.unsafe(...) 拼进 Pageable 的 Sort，最终效果不变，
        // 且从根上避免了同一条 JPQL 出现两个 ORDER BY 关键字导致的 QuerySyntaxException。
        // 2026-08-21："客户已结算"不再是终态（改成"已收到客户回款"之后才算），财务视角的
        // "优先展示"这一级也跟着扩围：处于 SETTLED 的记录同样还需要财务跟进（推进到
        // PAYMENT_RECEIVED），继续排在前面；下一级"是否已完成"的判断则相应地把 SETTLED 从
        // "已完成"里移出，改判 PAYMENT_RECEIVED，跟 CollaborationTrackingRepository.findByFilters
        // 的 onlyIncomplete 口径保持一致，见 CollaborationProgress 类注释。
        Sort sort = JpaSort.unsafe(Sort.Direction.ASC,
                        "CASE WHEN (:priorityEmployeeId IS NOT NULL " +
                        "AND (c.projectManagerId = :priorityEmployeeId OR c.executorId = :priorityEmployeeId)) " +
                        "OR (:prioritizeFinance = true AND c.progress IN (" +
                        "com.lusuoria.settlement.enums.CollaborationProgress.PUBLISHED_UNSETTLED, " +
                        "com.lusuoria.settlement.enums.CollaborationProgress.JOINED_CLIENT_UNSETTLED_LIST, " +
                        "com.lusuoria.settlement.enums.CollaborationProgress.SETTLED)) " +
                        "THEN 0 ELSE 1 END")
                .andUnsafe(Sort.Direction.ASC,
                        "CASE WHEN :onlyMyResponsibility = true AND :priorityEmployeeId IS NOT NULL " +
                        "AND c.progress NOT IN (" +
                        "com.lusuoria.settlement.enums.CollaborationProgress.PAYMENT_RECEIVED, " +
                        "com.lusuoria.settlement.enums.CollaborationProgress.DELAYED) " +
                        "THEN 0 ELSE 1 END")
                .and(Sort.by(userSortDirection, sortProperty));
        PageRequest pageable = PageRequest.of(page, size, sort);
        String videoMonthParam = (videoMonth == null || videoMonth.trim().isEmpty()) ? null : videoMonth.trim();
        // 视频发布日期区间：跟视频发布月份是两个独立的筛选参数，同时传了会一起生效（AND）——
        // 前端保证这两个互斥（选一个会清空另一个），这里不用互相覆盖，各自为 null 就不生效
        String videoDateStartParam = (videoDateStart == null || videoDateStart.trim().isEmpty()) ? null : videoDateStart.trim();
        String videoDateEndParam = (videoDateEnd == null || videoDateEnd.trim().isEmpty()) ? null : videoDateEnd.trim();
        // 默认（不点列头排序，仍是按 id）：未完成的项目排在前面，其次是还没加入结款批次的。
        // 这两档改在 Java 内存里做（见 buildIncompleteAndUnbatchedFirstPage 的注释——本来想跟
        // priorityEmployeeId 那两级一样直接拼 JpaSort.unsafe CASE WHEN，上线后触发了
        // Hibernate 的 QuerySyntaxException，回退后改成这个写法），不影响其他排序方式。
        Page<CollaborationTracking> result = "id".equals(sortBy)
                ? buildIncompleteAndUnbatchedFirstPage(
                        brandId, teamId, countryMarket, accountName, influencerId, platform,
                        progress, influencerPaymentProgress, videoType, videoMonthParam, videoDateStartParam, videoDateEndParam,
                        internalProjectNo, internalRequirementNo,
                        clientOrderId, clientPaymentBatch, projectManagerId,
                        priorityEmployeeId, prioritizeFinance, onlyMyResponsibility, onlyIncomplete, onlyUnpublished,
                        onlyMissingRequirementNo, sort, pageable)
                // progress/influencerPaymentProgress/videoType/projectManagerId 这4个多选筛选
                // 不能直接把可能为 null 的 List 传给 findByFilters，要先用 CollaborationFilterUtil
                // 算出 (xxxActive, xxx) 这一对参数，原因见 CollaborationTrackingRepository.findByFilters
                // 上方的说明（规避 Hibernate 经典 HQL 解析器的 "unexpected AST node: {vector}" bug）
                : trackingRepo.findByFilters(
                        brandId, teamId, countryMarket, accountName, influencerId, platform,
                        CollaborationFilterUtil.isActive(progress),
                        CollaborationFilterUtil.orPlaceholder(progress, CollaborationProgress.PENDING_CLIENT_BRIEF),
                        CollaborationFilterUtil.isActive(influencerPaymentProgress),
                        CollaborationFilterUtil.orPlaceholder(influencerPaymentProgress, InfluencerPaymentProgress.PENDING_INVOICE),
                        CollaborationFilterUtil.isActive(videoType),
                        CollaborationFilterUtil.orPlaceholder(videoType, VideoType.REAL_SHOT_NEW),
                        videoMonthParam, videoDateStartParam, videoDateEndParam,
                        internalProjectNo, internalRequirementNo,
                        clientOrderId, clientPaymentBatch,
                        CollaborationFilterUtil.isActive(projectManagerId),
                        CollaborationFilterUtil.orPlaceholder(projectManagerId, -1L),
                        priorityEmployeeId, prioritizeFinance, onlyMyResponsibility, onlyIncomplete, onlyUnpublished,
                        onlyMissingRequirementNo, pageable);

        // 批量标记"当前是否有待审核的删除申请 / 进度倒退申请"，避免逐行查库。
        // pendingDeleteIds  → 操作列"删除"链接变成灰色"审核中"文字（CollaborationListPage.vue
        //                     action 列 hasPendingDeleteRequest 分支）
        // pendingRollbackIds→ "状态流转"链接旁边的橙色"（倒退审核中）"提示（同一处 hasPendingRollbackRequest）
        Set<Long> pendingDeleteIds = new HashSet<>(pendingApprovalRepo.findPendingTargetIds(
                PendingApprovalModule.COLLABORATION_TRACKING, PendingApprovalCategory.DELETE_REQUEST));
        Set<Long> pendingRollbackIds = new HashSet<>(pendingApprovalRepo.findPendingTargetIds(
                PendingApprovalModule.COLLABORATION_TRACKING, PendingApprovalCategory.PROGRESS_ROLLBACK));

        result.forEach(t -> {
            t.setHasPendingDeleteRequest(pendingDeleteIds.contains(t.getId()));
            t.setHasPendingRollbackRequest(pendingRollbackIds.contains(t.getId()));
        });

        // 字段级可见性统一走 ProjectFieldVisibility：FULL（ADMIN/管理层/财务/AUDITOR）看全部，
        // 其余角色（含 GUEST）按分级规则脱敏，applyFieldVisibility 内部已经处理了所有分级——这一段
        // 决定的是列表页每一行"成本/提成"这类财务字段是显示真实值还是显示为空，不是某个按钮
        ProjectFieldVisibility.Context ctx = fieldVisibility.resolve();
        if (!ctx.isFull()) {
            return ApiResponse.success(result.map(t -> applyFieldVisibility(t, ctx)));
        }
        return ApiResponse.success(result);
    }

    /**
     * 默认排序（sortBy=id）下的"未完成的项目排前面，其次是未加入结款批次的"——2026-07 新增，
     * 2026-07 同日修正：本来直接拼两级 JpaSort.unsafe CASE WHEN 加进 ORDER BY（跟已有的
     * priorityEmployeeId 那两级一样），上线后触发 Hibernate QuerySyntaxException（把其中一个
     * CASE WHEN 表达式误当成安全属性路径，拼出非法 HQL "c.CASE WHEN ..."）——本地没有数据库
     * 环境，没法在改动前验证这类多级 unsafe 链式调用在这个 Hibernate 版本下到底安不安全，只能
     * 线上出问题后再回退，索性改成风险更低的做法：先用轻量投影（只取 id/progress/
     * influencerPaymentId 三列，不查完整实体）把所有命中记录按"已经在正常工作的排序"
     * （priorityEmployeeId 那两级 + 用户选的列排序，这里固定是 id）取出来，在 Java 里按
     * (是否未完成, 是否未加入结款批次) 做稳定分桶（桶内相对顺序不变），再手动分页，只对
     * "当前页"这一小撮 id 才去查完整实体——跟 InfluencerRequirementService.listIncompleteFirst
     * 是同一套思路（那边也是因为同样的顾虑，从一开始就没有尝试往 SQL ORDER BY 里加这层逻辑）。
     */
    private Page<CollaborationTracking> buildIncompleteAndUnbatchedFirstPage(
            Long brandId, Long teamId, String countryMarket, String accountName, Long influencerId, String platform,
            List<CollaborationProgress> progress, List<InfluencerPaymentProgress> influencerPaymentProgress,
            List<VideoType> videoType,
            String videoMonthParam, String videoDateStartParam, String videoDateEndParam,
            String internalProjectNo, String internalRequirementNo,
            String clientOrderId, String clientPaymentBatch, List<Long> projectManagerId,
            Long priorityEmployeeId, boolean prioritizeFinance, boolean onlyMyResponsibility, boolean onlyIncomplete,
            boolean onlyUnpublished, boolean onlyMissingRequirementNo, Sort sort, Pageable pageable) {
        // 第一步：轻量投影查询——只取 id/progress/影响分桶用的结款批次id 这三列（不是完整实体），
        // 但排序（sort 参数）已经在这条 SQL 里生效了，所以每个桶内部的记录相对顺序，就是
        // "已经在正常工作的排序"（优先展示 + 用户选的列排序），下面分桶只是在这基础上再分组，
        // 不会打乱桶内已经排好的顺序
        // progress/influencerPaymentProgress/videoType/projectManagerId 这4个多选筛选同样要
        // 先转成 (xxxActive, xxx) 这一对参数再传下去，原因见 CollaborationTrackingRepository.
        // findByFilters 上方的说明（规避 Hibernate 经典 HQL 解析器的"unexpected AST node:
        // {vector}"bug），跟上面 list() 直接调 findByFilters 那条分支用的是同一套转换逻辑
        List<Object[]> liteRows = trackingRepo.findLitePriorityProjectionByFilters(
                brandId, teamId, countryMarket, accountName, influencerId, platform,
                CollaborationFilterUtil.isActive(progress),
                CollaborationFilterUtil.orPlaceholder(progress, CollaborationProgress.PENDING_CLIENT_BRIEF),
                CollaborationFilterUtil.isActive(influencerPaymentProgress),
                CollaborationFilterUtil.orPlaceholder(influencerPaymentProgress, InfluencerPaymentProgress.PENDING_INVOICE),
                CollaborationFilterUtil.isActive(videoType),
                CollaborationFilterUtil.orPlaceholder(videoType, VideoType.REAL_SHOT_NEW),
                videoMonthParam, videoDateStartParam, videoDateEndParam,
                internalProjectNo, internalRequirementNo,
                clientOrderId, clientPaymentBatch,
                CollaborationFilterUtil.isActive(projectManagerId),
                CollaborationFilterUtil.orPlaceholder(projectManagerId, -1L),
                priorityEmployeeId, prioritizeFinance, onlyMyResponsibility, onlyIncomplete, onlyUnpublished,
                onlyMissingRequirementNo, sort);

        // 第二步：在内存里把每条记录的 id 按 (是否未完成, 是否未加入结款批次) 分进 4 个桶——
        // 这一步只处理 id，不涉及完整实体
        // 2026-08-21："已完成"的判定标准从 SETTLED 改成 PAYMENT_RECEIVED（"客户已结算"不再是
        // 终态），口径统一跟 findLitePriorityProjectionByFilters 的 onlyIncomplete 保持一致
        List<Long> bucket0 = new ArrayList<>(); // 未完成 + 未加入结款批次
        List<Long> bucket1 = new ArrayList<>(); // 未完成 + 已加入结款批次
        List<Long> bucket2 = new ArrayList<>(); // 已完成 + 未加入结款批次
        List<Long> bucket3 = new ArrayList<>(); // 已完成 + 已加入结款批次
        for (Object[] row : liteRows) {
            Long id = ((Number) row[0]).longValue();
            CollaborationProgress p = (CollaborationProgress) row[1];
            Long paymentId = row[2] != null ? ((Number) row[2]).longValue() : null;
            boolean incomplete = p == null || (p != CollaborationProgress.PAYMENT_RECEIVED && p != CollaborationProgress.DELAYED);
            boolean unbatched = paymentId == null;
            if (incomplete && unbatched) bucket0.add(id);
            else if (incomplete) bucket1.add(id);
            else if (unbatched) bucket2.add(id);
            else bucket3.add(id);
        }
        // 按桶的优先级顺序（0→1→2→3）依次拼接，就得到最终展示顺序：未完成的两桶排在已完成的
        // 两桶前面，每个桶内部保留第一步查询自带的排序
        List<Long> orderedIds = new ArrayList<>(liteRows.size());
        orderedIds.addAll(bucket0);
        orderedIds.addAll(bucket1);
        orderedIds.addAll(bucket2);
        orderedIds.addAll(bucket3);

        // 第三步：在这份重排好的 id 列表上手动切出"当前页"对应的下标区间（分页游标没法直接
        // 应用在数据库查询上，因为排序结果是分桶重排过的，只能落到内存里手动分页）
        int total = orderedIds.size();
        int start = Math.min((int) pageable.getOffset(), total);
        int end = Math.min(start + pageable.getPageSize(), total);
        List<Long> pageIds = orderedIds.subList(start, end);

        // 第四步：只对"当前页"这一小撮 id 才去查完整实体——真正体积大的数据到这一步才第一次
        // 触发。findAllById 返回顺序不保证跟 pageIds 一致（同 InfluencerController#list 里的
        // 同类写法），所以先转成 Map 再按 pageIds 的顺序重新取一遍，保证展示顺序不丢
        Map<Long, CollaborationTracking> byId = trackingRepo.findAllById(pageIds).stream()
                .collect(Collectors.toMap(CollaborationTracking::getId, t -> t));
        List<CollaborationTracking> pageContent = new ArrayList<>();
        for (Long id : pageIds) {
            CollaborationTracking t = byId.get(id);
            if (t != null) pageContent.add(t);
        }
        // 总数/分页信息是手动算的，用 PageImpl 包装成标准 Page 对象返回，前端不用区分这个接口
        // 分页是不是"手动"的
        return new org.springframework.data.domain.PageImpl<>(pageContent, pageable, total);
    }

    /**
     * "优先展示"用：当前登录人是项目负责人/执行人员时，返回其员工 id（列表页会把自己是
     * 负责人/执行人员的记录排到最前面）；管理层是一个特殊的项目负责人（部分红人合作跟踪记录的
     * projectManagerId 直接指向管理层本人，见 CollaborationTrackingExcelHandler/前端负责人下拉
     * 都允许选"管理层"角色），所以这里跟"项目负责人"一视同仁；其余角色（财务/ADMIN/AUDITOR/
     * GUEST等）返回 null，list() 里拼进 Sort 的那两级 CASE 分支不生效。
     */
    private Long resolvePriorityEmployeeId() {
        String role = employeeRoleUtil.getCurrentEmployeeRole();
        if ("项目负责人".equals(role) || "执行人员".equals(role) || "管理层".equals(role)) {
            return employeeRoleUtil.getCurrentEmployeeId();
        }
        return null;
    }

    /**
     * "优先展示"用：当前登录人是财务时，视频项目进度是"已发布（未结算）"/"已加入客户未结算
     * 列表"（财务需要处理的两个阶段）的记录排到最前面。
     */
    private boolean resolvePrioritizeFinance() {
        return "财务".equals(employeeRoleUtil.getCurrentEmployeeRole());
    }

    /** 单条合作跟踪记录详情，同样按字段可见性分级脱敏（跟 list() 用的是同一套 applyFieldVisibility） */
    @GetMapping("/{id}")
    public ApiResponse<CollaborationTracking> getById(@PathVariable Long id) {
        CollaborationTracking t = trackingRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("跟踪记录不存在"));
        ProjectFieldVisibility.Context ctx = fieldVisibility.resolve();
        if (!ctx.isFull()) t = applyFieldVisibility(t, ctx);
        return ApiResponse.success(t);
    }

    /**
     * 红人管理"合作中项目/已完结项目"下钻弹窗用（2026-07 新增）：某个红人 + 类别
     * （ACTIVE=进行中，COMPLETED=已完结）的合作跟踪记录分页 + 汇总（汇总覆盖全部命中记录，
     * 不只是当前这一页）。字段脱敏复用跟列表页/详情完全一样的 applyFieldVisibility。
     */
    @GetMapping("/by-influencer")
    public ApiResponse<InfluencerCollaborationPageResponse> listByInfluencer(
            @RequestParam Long influencerId,
            @RequestParam String category,
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) VideoType videoType,
            @RequestParam(required = false) CollaborationProgress progress,
            @RequestParam(required = false) Long projectManagerId,
            @RequestParam(required = false) String videoMonth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        size = Math.max(1, Math.min(size, 200));
        boolean completed = "COMPLETED".equalsIgnoreCase(category);
        String videoMonthParam = (videoMonth == null || videoMonth.trim().isEmpty()) ? null : videoMonth.trim();
        // 这一页命中记录（已经是完整实体，这个下钻弹窗数据量小，不需要 list() 那套轻量投影优化）
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        Page<CollaborationTracking> result = trackingRepo.findByInfluencerAndCompletionStatus(
                influencerId, completed, brandId, teamId, platform, videoType, progress,
                projectManagerId, videoMonthParam, pageable);

        // 字段脱敏（跟列表页/详情同一套 ProjectFieldVisibility），只对当前页这几条做
        ProjectFieldVisibility.Context ctx = fieldVisibility.resolve();
        boolean canSeeBaseline = ctx.tier != ProjectFieldVisibility.Tier.GUEST;
        if (!ctx.isFull()) {
            result = result.map(t -> applyFieldVisibility(t, ctx));
        }

        // 汇总数字（合计条数/总成本/总价格）是另一条独立的聚合 SQL，覆盖全部命中记录，
        // 不受当前页限制——分页只影响下面列表展示，不影响这里的合计
        List<Object[]> sumRows = trackingRepo.sumByInfluencerAndCompletionStatus(
                influencerId, completed, brandId, teamId, platform, videoType, progress,
                projectManagerId, videoMonthParam);
        Object[] sums = !sumRows.isEmpty() ? sumRows.get(0)
                : new Object[]{0L, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO};
        InfluencerCollaborationPageResponse resp = new InfluencerCollaborationPageResponse();
        resp.setPage(result);
        resp.setTotalCount(((Number) sums[0]).longValue());
        // GUEST 看不到"红人视频制作与发布成本"/"客户合作价格"这两个明细字段，汇总数字也不该露出来
        resp.setTotalInfluencerCost(canSeeBaseline ? (java.math.BigDecimal) sums[1] : null);
        resp.setTotalClientPrice(canSeeBaseline ? (java.math.BigDecimal) sums[2] : null);
        return ApiResponse.success(resp);
    }

    @lombok.Data
    public static class InfluencerCollaborationPageResponse {
        private Page<CollaborationTracking> page;
        private Long totalCount;
        private java.math.BigDecimal totalInfluencerCost;
        private java.math.BigDecimal totalClientPrice;
    }

    /**
     * 新建/编辑单条合作跟踪记录（req.getId() 为空即新建）。真正的业务逻辑全在
     * CollaborationTrackingService.save()/doSave() 里（自动编号分配、内部执行成本自动计算、
     * 提成比例校验等），这里只是脱个敏再返回。
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<CollaborationTracking> save(@Valid @RequestBody CollaborationTrackingRequest req) {
        try {
            CollaborationTracking saved = trackingService.save(req);
            ProjectFieldVisibility.Context ctx = fieldVisibility.resolve();
            CollaborationTracking out = ctx.isFull() ? saved : applyFieldVisibility(saved, ctx);
            return ApiResponse.success(out);
        } catch (CollaborationTrackingService.DuplicateTrackingException e) {
            return ApiResponse.error(CODE_DUPLICATE, e.getMessage());
        }
    }

    /**
     * "复制"批量新建：新建弹窗左侧克隆出的多个"视频项目"面板一次性提交，整批在一个事务里
     * 校验+保存，任何一条失败（含关联需求超量）都整批回滚，前端据此保留所有面板不关闭。
     */
    @PostMapping("/batch")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<List<CollaborationTracking>> createBatch(@Valid @RequestBody List<CollaborationTrackingRequest> reqs) {
        List<CollaborationTracking> saved = trackingService.createBatch(reqs);
        ProjectFieldVisibility.Context ctx = fieldVisibility.resolve();
        List<CollaborationTracking> out = ctx.isFull() ? saved
                : saved.stream().map(t -> applyFieldVisibility(t, ctx)).collect(java.util.stream.Collectors.toList());
        return ApiResponse.success(out);
    }

    /**
     * 发起删除申请（不直接删除）：填写删除原因后生成一条"待处理"审核事项，
     * 由 ADMIN 在"待处理"模块同意后才真正删除。
     */
    @PostMapping("/{id}/delete-request")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<PendingApproval> requestDelete(
            @PathVariable Long id, @Valid @RequestBody DeleteRequestReasonRequest req) {
        return ApiResponse.success(trackingService.requestDelete(id, req.getReason()));
    }

    /**
     * 状态流转：主要修改"视频项目进度"/"红人结款进度"这两个字段，配合前端专门的"状态流转"弹窗
     * 使用，防止编辑状态时误改其他内容；另外两个字段（notes/clientPaymentBatch）只在特定目标
     * 进度下才会被这个接口写入，见下面说明。
     *
     * 正常情况下改动立即生效；但如果是"视频项目进度"从符合条件的状态倒退回不符合条件的状态、
     * 且当前记录"红人结款进度"已经有值，这种改动不会立即生效，而是提交一条待审核事项，
     * 由管理员在"待处理"模块同意后才真正生效（见 CollaborationTrackingService.updateStatus）。
     * "折损"不算这里说的倒退（2026-07 修复），任何状态都能直接流转到"折损"、不需要审核，
     * 但要求同步填写 notes（备注），提交后会直接覆盖更新到这条记录。
     *
     * 2026-07 起放开给 AUDITOR（财务账号通常是这个 SysUser 角色）：财务需要能把"已发布
     * （未结算）"流转到"已加入客户未结算列表"/"客户已结算"这两个财务专属终态。AUDITOR
     * 能不能真的改、能改成什么，由 Service 内部按"只能在已经进入结算区间的三个阶段之间流转"
     * 这条规则精确校验（见 updateStatus 里的 AUDITOR 专属校验），不是这里简单放行了事。
     * 员工角色精确是"财务"的账号流转到"客户已结算"时，同样要求同步填写 clientPaymentBatch
     * （客户方付款批次单号），提交后会直接覆盖更新到这条记录。
     */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF','AUDITOR')")
    public ApiResponse<CollaborationStatusUpdateResult> updateStatus(
            @PathVariable Long id, @RequestBody CollaborationTrackingStatusRequest req) {
        CollaborationStatusUpdateResult result = trackingService.updateStatus(id, req);
        ProjectFieldVisibility.Context ctx = fieldVisibility.resolve();
        if (!ctx.isFull()) {
            result.setTracking(applyFieldVisibility(result.getTracking(), ctx));
        }
        return ApiResponse.success(result);
    }

    /**
     * 内部执行成本弹窗打开时调用：只读，算出默认建议金额 + 算出来的依据说明，不修改任何数据。
     * executorId 可选：弹窗里如果这条记录还没选执行人员、用户现场选了一个人，传这个参数可以
     * 现算这个人的建议金额；不传就退回这条记录数据库里已存的执行人员。
     */
    @GetMapping("/{id}/executor-cost-suggestion")
    public ApiResponse<ExecutorCostSuggestionResponse> suggestExecutorCost(
            @PathVariable Long id, @RequestParam(required = false) Long executorId) {
        return ApiResponse.success(trackingService.suggestExecutorCost(id, executorId));
    }

    /**
     * 内部执行成本弹窗确认时调用：保存金额（或确认"不涉及执行人员"），跟状态流转是分开的两步操作。
     *
     * 2026-07 起"非首次修改"且操作人不是该记录的项目负责人本人时不会直接生效，见
     * CollaborationTrackingService.setExecutorCost()——返回结果的 pendingApproval=true
     * 时，tracking 仍是改动前的原始记录，前端应提示"已提交项目负责人审核"，不能当成已保存成功。
     */
    @PatchMapping("/{id}/executor-cost")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<ExecutorCostUpdateResult> setExecutorCost(
            @PathVariable Long id, @RequestBody ExecutorCostRequest req) {
        // 2026-08 起金额不再由前端传入，服务层按费率梯度现场算，见
        // CollaborationTrackingService.setExecutorCost() 的注释
        ExecutorCostUpdateResult result = trackingService.setExecutorCost(
                id, req.getExecutorId(), req.isNotApplicable());
        ProjectFieldVisibility.Context ctx = fieldVisibility.resolve();
        if (!ctx.isFull()) {
            result.setTracking(applyFieldVisibility(result.getTracking(), ctx));
        }
        return ApiResponse.success(result);
    }

    /** 内部执行成本保存请求体 */
    @lombok.Data
    public static class ExecutorCostRequest {
        private Long executorId;
        private boolean notApplicable;
    }

    /**
     * 解除跟"红人需求管理"内部需求编号的关联（误关联到别的需求时用）。权限收窄成该记录的
     * 项目负责人/执行人员或 ADMIN，见 CollaborationTrackingService.unlinkRequirement。
     */
    @PatchMapping("/{id}/unlink-requirement")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<CollaborationTracking> unlinkRequirement(@PathVariable Long id) {
        CollaborationTracking saved = trackingService.unlinkRequirement(id);
        ProjectFieldVisibility.Context ctx = fieldVisibility.resolve();
        CollaborationTracking out = ctx.isFull() ? saved : applyFieldVisibility(saved, ctx);
        return ApiResponse.success(out);
    }

    /**
     * 批量重新计算所有记录的毛利/可分配利润/提成/公司利润，顺带修复汇率缺失/异常的记录
     * （仅 ADMIN）。用途见 CollaborationTrackingService.recomputeAllProfits() 的说明——主要是
     * 给"有人绕过系统直接改了数据库里的红人成本/客户合作价格之类原始值"、以及"记录的发布
     * 时间是后补的、错过了汇率维护那次批量覆盖"这两种情况做善后。
     */
    @PostMapping("/recompute-profits")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<String> recomputeProfits() {
        return ApiResponse.success(trackingService.recomputeAllProfits());
    }

    /**
     * "批量计算执行成本"按钮：范围严格限定在当前登录员工自己身上（不是 ADMIN 的全表按钮），
     * 具体范围由 CollaborationTrackingService.recomputeExecutorCostsScoped() 按员工角色判定——
     * 管理层不限定（等同"特殊的项目负责人"），项目负责人只处理自己名下的，执行人员只处理自己
     * 执行的。前端只对这三种员工角色展示按钮，但权限校验必须在服务层再做一遍（见该方法注释），
     * 不能只靠前端隐藏按钮。
     */
    @PostMapping("/recompute-executor-costs")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<String> recomputeExecutorCosts() {
        String employeeRole = employeeRoleUtil.getCurrentEmployeeRole();
        Long employeeId = employeeRoleUtil.getCurrentEmployeeId();
        return ApiResponse.success(trackingService.recomputeExecutorCostsScoped(employeeRole, employeeId));
    }

    // ============ Excel ============
    @GetMapping("/export/excel")
    public void exportExcel(
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String countryMarket,
            @RequestParam(required = false) String accountName,
            @RequestParam(required = false) Long influencerId,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) List<CollaborationProgress> progress,
            @RequestParam(required = false) List<InfluencerPaymentProgress> influencerPaymentProgress,
            @RequestParam(required = false) List<VideoType> videoType,
            @RequestParam(required = false) String videoMonth,
            @RequestParam(required = false) String videoDateStart,
            @RequestParam(required = false) String videoDateEnd,
            @RequestParam(required = false) String internalProjectNo,
            @RequestParam(required = false) String internalRequirementNo,
            @RequestParam(required = false) String clientOrderId,
            @RequestParam(required = false) String clientPaymentBatch,
            @RequestParam(required = false) List<Long> projectManagerId,
            @RequestParam(defaultValue = "false") boolean onlyMyResponsibility,
            @RequestParam(defaultValue = "false") boolean onlyIncomplete,
            @RequestParam(defaultValue = "false") boolean onlyUnpublished,
            @RequestParam(defaultValue = "false") boolean onlyMissingRequirementNo,
            HttpServletResponse response) throws IOException {
        // 导出按当前筛选条件，取全部（不分页）
        PageRequest all = PageRequest.of(0, Integer.MAX_VALUE, Sort.by(Sort.Direction.DESC, "id"));
        String videoMonthParam = (videoMonth == null || videoMonth.trim().isEmpty()) ? null : videoMonth.trim();
        String videoDateStartParam = (videoDateStart == null || videoDateStart.trim().isEmpty()) ? null : videoDateStart.trim();
        String videoDateEndParam = (videoDateEnd == null || videoDateEnd.trim().isEmpty()) ? null : videoDateEnd.trim();
        // 2026-08 修复（Shawn 反馈）：之前这里无条件传 influencerId=null、
        // onlyMyResponsibility/onlyIncomplete/onlyUnpublished/onlyMissingRequirementNo
        // 全部传 false，不管前端实际筛选状态是什么，导致点了"查看未绑定需求编号的记录"这类
        // 快捷筛选按钮（或红人管理下钻带的 influencerId 精确筛选）之后再导出，导出的还是
        // 忽略这些筛选条件的全量/半全量数据，跟列表页当前看到的对不上。改成跟 list() 一样
        // 老老实实按前端传来的这几个参数筛，导出结果才会跟列表页当前筛选状态一致。
        // priorityEmployeeId/prioritizeFinance 只有 onlyMyResponsibility=true 时才真正影响
        // 筛选结果（findByFilters 的 WHERE 子句），但跟 list() 一样无条件算一次，成本很低。
        Long priorityEmployeeId = resolvePriorityEmployeeId();
        boolean prioritizeFinance = resolvePrioritizeFinance();
        // 同 list() 的处理：多选筛选要先转成 (xxxActive, xxx) 参数对，见
        // CollaborationTrackingRepository.findByFilters 上方对 "unexpected AST node: {vector}"
        // 这个 Hibernate 解析器 bug 的说明
        List<CollaborationTracking> list = trackingRepo.findByFilters(
                brandId, teamId, countryMarket, accountName, influencerId, platform,
                CollaborationFilterUtil.isActive(progress),
                CollaborationFilterUtil.orPlaceholder(progress, CollaborationProgress.PENDING_CLIENT_BRIEF),
                CollaborationFilterUtil.isActive(influencerPaymentProgress),
                CollaborationFilterUtil.orPlaceholder(influencerPaymentProgress, InfluencerPaymentProgress.PENDING_INVOICE),
                CollaborationFilterUtil.isActive(videoType),
                CollaborationFilterUtil.orPlaceholder(videoType, VideoType.REAL_SHOT_NEW),
                videoMonthParam, videoDateStartParam, videoDateEndParam,
                internalProjectNo, internalRequirementNo,
                clientOrderId, clientPaymentBatch,
                CollaborationFilterUtil.isActive(projectManagerId),
                CollaborationFilterUtil.orPlaceholder(projectManagerId, -1L),
                priorityEmployeeId, prioritizeFinance, onlyMyResponsibility, onlyIncomplete, onlyUnpublished,
                onlyMissingRequirementNo, all).getContent();
        // canViewFull：汇率/其他外部成本/内部执行成本/毛利/提成/公司利润这些财务字段，
        // 只有导出的人是 ADMIN/AUDITOR，或员工角色是"管理层"/"财务"才包含在导出文件里，
        // 复用 ProjectFieldVisibility 的 FULL 层级判定，跟列表页/表单页这批字段的可见规则一致
        boolean canViewFull = fieldVisibility.resolve().isFull();
        excelHandler.export(list, RoleUtil.canViewBaselineFinancials(), canViewFull, response);
    }

    /** 下载 Excel 批量导入模板（列结构按当前账号能看到的字段档位裁剪，见 excelHandler 内部） */
    @GetMapping("/template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        excelHandler.downloadTemplate(RoleUtil.canViewBaselineFinancials(), response);
    }

    /**
     * Excel 批量导入入口：立即建一条 ImportBatch（状态 PROCESSING）并返回其 id，真正的解析/落库
     * 在后台线程跑，前端拿这个 id 轮询 ImportBatchController 查进度（见下方注释）。
     *
     * 2026-08-21 起额外放行员工角色="财务"的账号：财务通常是 SysUser 角色 AUDITOR（"全字段
     * 只读+导出"），@PreAuthorize 原来只放行 ADMIN/STAFF，AUDITOR 会被直接 403 挡在外面。
     * 这里放宽成 AUDITOR 也能过注解这一关，再在方法体里精确校验"必须是财务"——不能只看
     * SysUser 角色是 AUDITOR 就放行，AUDITOR 也可能是法务这类别的只读岗位，见
     * CollaborationTrackingService.updateStatus() 里同样的"AUDITOR 不等于财务"注释。
     */
    @PostMapping("/import/excel")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF','AUDITOR')")
    public ApiResponse<Long> importExcel(@RequestParam("file") MultipartFile file) throws IOException {
        if ("AUDITOR".equals(RoleUtil.getCurrentRole()) && !"财务".equals(employeeRoleUtil.getCurrentEmployeeRole())) {
            throw new RuntimeException("无权限执行此操作");
        }
        // 导入改成异步了：数据量一大（比如几百行），同步等待整个导入跑完很容易撞到
        // 浏览器/反向代理的超时限制。这里立即建一条"导入批次"记录、马上把 id 返回给前端，
        // 实际的导入过程丢到后台线程慢慢跑，前端可以去"导入历史"页面随时查进度和结果。
        ImportBatch batch = new ImportBatch();
        batch.setModule("COLLABORATION_TRACKING");
        batch.setFileName(file.getOriginalFilename());
        batch.setUploadedByName(RoleUtil.getCurrentUsername());
        batch.setStatus("PROCESSING");
        batch.setStartedAt(new java.util.Date());
        batch = importBatchRepo.save(batch);

        byte[] fileBytes = file.getBytes(); // 必须先读成字节数组，HTTP 请求结束后原始文件流就用不了了
        // 下面几个"当前操作人是谁/什么权限"的判断，必须在这里、当前 HTTP 请求线程里现场算好
        // 传下去——异步导入线程池没有 SecurityContext，进去以后再解析登录态只会拿到空的
        // （2026-08 修复：ADMIN/管理层账号导入带"已加入客户未结算列表"/"客户已结算"这两个状态的行、
        // 或者导入改动了已有记录"内部执行人员"的行，会被无差别拒绝），见
        // CollaborationTrackingService.requireFinanceForSettlementProgress() 和 doSave() 里
        // "内部执行人员"那段校验的注释
        // 2026-08 起：除了 isFull（ADMIN/AUDITOR，或员工角色=管理层/财务）以外，员工角色是
        // 项目负责人/执行人员/IT后勤也放行（Shawn 反馈），见 EmployeeRoleUtil.canSetSettlementProgressExtraRole()
        boolean canSetFinanceSettlementProgress = fieldVisibility.resolve().isFull()
                || employeeRoleUtil.canSetSettlementProgressExtraRole();
        boolean isAdminOrManagement = RoleUtil.isAdmin() || "管理层".equals(employeeRoleUtil.getCurrentEmployeeRole());
        Long currentEmployeeId = employeeRoleUtil.getCurrentEmployeeId();
        // 2026-08-21 新增（Shawn 要求）：员工角色="财务"的账号导入时只允许"更新"已有记录，
        // 新增一律拒绝——ADMIN 排除在外（即便某个 ADMIN 账号巧合关联了"财务"员工角色，也不受
        // 这条限制约束，ADMIN 在这套权限体系里历来都是完全放行的），见
        // CollaborationTrackingExcelHandler.importData() 里 financeImportUpdateOnly 的用法
        boolean financeImportUpdateOnly = !RoleUtil.isAdmin() && "财务".equals(employeeRoleUtil.getCurrentEmployeeRole());
        excelHandler.importDataAsync(batch.getId(), fileBytes, RoleUtil.canViewBaselineFinancials(),
                canSetFinanceSettlementProgress, isAdminOrManagement, currentEmployeeId, financeImportUpdateOnly);
        return ApiResponse.success(batch.getId());
    }

    // ============ "批量标记为已收到客户回款"（2026-08-21 新增） ============

    /**
     * 弹窗打开时调用：按前端传来的当前列表页筛选条件（跟 list() 的筛选栏一一对应，直接把
     * filters 对象整个序列化成请求体），取出全部命中记录（不分页），按"客户方付款批次"分组
     * 统计条数/客户合作总价格供人核对，不修改任何数据。用 POST 而不是 GET 是因为筛选条件
     * 字段多、还带数组（多选筛选），放 query string 不如放请求体干净。
     */
    @PostMapping("/payment-received/preview")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF','AUDITOR')")
    public ApiResponse<PaymentReceivedPreviewResponse> previewMarkPaymentReceived(
            @RequestBody MarkPaymentReceivedRequest req) {
        return ApiResponse.success(trackingService.markClientPaymentReceivedPreview(req));
    }

    /**
     * 弹窗确认提交调用：把预览接口同一份筛选条件命中的全部记录统一流转成"已收到客户回款"，
     * 并写入这次提交的收到回款日期。权限收窄到财务/管理层（比状态流转弹窗更严格），
     * 校验细节见 CollaborationTrackingService.markClientPaymentReceivedBulk()。
     */
    @PostMapping("/payment-received/confirm")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF','AUDITOR')")
    public ApiResponse<Integer> confirmMarkPaymentReceived(@RequestBody MarkPaymentReceivedRequest req) {
        return ApiResponse.success(trackingService.markClientPaymentReceivedBulk(req));
    }

    /**
     * 按当前登录账号的字段可见等级，对一条跟踪记录做脱敏（返回一个新副本，不改动原对象）。
     * 规则跟原来"项目订单"模块的 ProjectFieldVisibility 基本一致，2026-07 收紧了一处：
     *   - 红人成本/客户合作价格：GUEST 之外都能看
     *   - 汇率/其他外部成本/外部成本备注：仅 FULL（ADMIN/AUDITOR，或员工角色=管理层/财务）
     *     可见——项目负责人/执行人员一律看不到，不再有"自己负责的记录能看"这个例外
     *     （2026-07 从"项目负责人可见自己记录"改成"完全不可见"，跟前端记账字段收紧保持一致）
     *   - 内部执行成本：FULL 都能看；项目负责人/执行人员仅自己相关的记录能看，其余脱敏
     *   - 提成比例/提成金额：FULL 都能看；项目负责人仅自己负责的记录只读可见，其余脱敏
     *   - 项目毛利/可分配利润/公司利润(美金/人民币)：仅 FULL 可见
     */
    private CollaborationTracking applyFieldVisibility(CollaborationTracking t, ProjectFieldVisibility.Context ctx) {
        CollaborationTracking copy = new CollaborationTracking();
        BeanUtils.copyProperties(t, copy);

        boolean isOwnManager = ctx.employeeId != null && t.getProjectManagerId() != null
                && ctx.employeeId.equals(t.getProjectManagerId());
        boolean isOwnExecutor = ctx.employeeId != null && t.getExecutorId() != null
                && ctx.employeeId.equals(t.getExecutorId());
        boolean isManagerTier  = ctx.tier == ProjectFieldVisibility.Tier.PROJECT_MANAGER;
        boolean isExecutorTier = ctx.tier == ProjectFieldVisibility.Tier.EXECUTOR;

        boolean canSeeBaseline = ctx.tier != ProjectFieldVisibility.Tier.GUEST;
        if (!canSeeBaseline) {
            copy.setInfluencerCost(null);
            copy.setClientPrice(null);
        }

        if (!ctx.isFull()) {
            copy.setExchangeRate(null);
            copy.setOtherExternalCost(null);
            copy.setOtherExternalCostNote(null);
        }

        if (!(ctx.isFull() || (isManagerTier && isOwnManager) || (isExecutorTier && isOwnExecutor))) {
            copy.setInternalExecutionCost(null);
        }

        if (!(ctx.isFull() || (isManagerTier && isOwnManager))) {
            copy.setCommissionRate(null);
            copy.setCommissionAmount(null);
        }

        if (!ctx.isFull()) {
            copy.setGrossProfit(null);
            copy.setDistributableProfit(null);
            copy.setCompanyNetProfit(null);
            copy.setRmbRevenue(null);
        }

        return copy;
    }
}
