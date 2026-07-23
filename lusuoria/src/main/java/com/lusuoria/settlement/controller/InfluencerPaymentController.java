package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.dto.request.InfluencerPaymentRequest;
import com.lusuoria.settlement.dto.request.InfluencerPaymentStatusRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.dto.response.PaymentCandidateItem;
import com.lusuoria.settlement.entity.InfluencerPayment;
import com.lusuoria.settlement.enums.InfluencerPaymentStatus;
import com.lusuoria.settlement.excel.InfluencerPaymentExcelHandler;
import com.lusuoria.settlement.repository.CollaborationTrackingRepository;
import com.lusuoria.settlement.repository.InfluencerPaymentRepository;
import com.lusuoria.settlement.repository.InfluencerPaymentTeamRepository;
import com.lusuoria.settlement.service.impl.InfluencerPaymentService;
import com.lusuoria.settlement.util.PaymentAccessUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 红人结款。权限严格按员工角色判断（PaymentAccessUtil），不是按 SysUser.role，
 * 所以不用 @PreAuthorize，改成方法体内手动判定（参照 ProgressReminderController）。
 */
@RestController
@RequestMapping("/api/influencer-payments")
public class InfluencerPaymentController {

    @Autowired private InfluencerPaymentRepository paymentRepo;
    @Autowired private InfluencerPaymentTeamRepository paymentTeamRepo;
    @Autowired private CollaborationTrackingRepository trackingRepo;
    @Autowired private InfluencerPaymentService paymentService;
    @Autowired private InfluencerPaymentExcelHandler excelHandler;
    @Autowired private PaymentAccessUtil accessUtil;

    @GetMapping
    public ApiResponse<Page<InfluencerPayment>> list(
            @RequestParam(required = false) String settlementMonth,
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String internalRequirementNo,
            @RequestParam(required = false) InfluencerPaymentStatus paymentStatus,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        if (!accessUtil.canView()) return ApiResponse.error(403, "无权限查看红人结款");
        // "涉及哪些团队"现在是关联表，不是这张表自己的列，按团队筛选先查出符合条件的结款记录 id。
        // matchingIds 恒不为空——JPQL 的 IN 子句绑定空集合在部分 Hibernate 版本下会报错，
        // 不筛选团队或者查不到匹配结果时都传占位不存在的 id，交给 filterByTeam 决定要不要用它
        boolean filterByTeam = teamId != null;
        List<Long> matchingIds = filterByTeam ? paymentTeamRepo.findPaymentIdsByTeamId(teamId) : Collections.emptyList();
        if (matchingIds.isEmpty()) matchingIds = Collections.singletonList(-1L);
        // 内部需求编号筛选：一个结款批次下可能挂着多个不同需求编号的合作跟踪记录，
        // 只要批次里"有至少一条"匹配这个需求编号就算命中，跟按团队筛选是同一个套路
        boolean filterByReqNo = internalRequirementNo != null && !internalRequirementNo.trim().isEmpty();
        List<Long> reqMatchingIds = filterByReqNo
                ? trackingRepo.findPaymentIdsByRequirementNo(internalRequirementNo.trim()) : Collections.emptyList();
        if (reqMatchingIds.isEmpty()) reqMatchingIds = Collections.singletonList(-1L);
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "settlementMonth"));
        Page<InfluencerPayment> result = paymentRepo.findByFilters(
                settlementMonth, brandId, filterByTeam, matchingIds, filterByReqNo, reqMatchingIds, paymentStatus, pageable);
        paymentService.attachTeamIds(result.getContent());
        return ApiResponse.success(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<InfluencerPayment> getById(@PathVariable Long id) {
        if (!accessUtil.canView()) return ApiResponse.error(403, "无权限查看红人结款");
        InfluencerPayment payment = paymentRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("结款记录不存在"));
        paymentService.attachTeamIds(Collections.singletonList(payment));
        return ApiResponse.success(payment);
    }

    /** "选择涉及的红人视频项目"弹窗 - 候选列表 */
    @GetMapping("/candidates")
    public ApiResponse<List<PaymentCandidateItem>> candidates(
            @RequestParam Long brandId,
            @RequestParam(required = false) List<Long> teamIds,
            @RequestParam(defaultValue = "false") boolean includeNoTeam,
            @RequestParam(required = false) String reconcileDate) {
        if (!accessUtil.canView()) return ApiResponse.error(403, "无权限查看红人结款");
        Date parsed = parseDate(reconcileDate);
        return ApiResponse.success(paymentService.listCandidates(brandId, teamIds, includeNoTeam, parsed));
    }

    /** 某条结款记录已纳入的红人合作跟踪明细 */
    @GetMapping("/{id}/items")
    public ApiResponse<List<PaymentCandidateItem>> items(@PathVariable Long id) {
        if (!accessUtil.canView()) return ApiResponse.error(403, "无权限查看红人结款");
        return ApiResponse.success(paymentService.listItems(id));
    }

    /** 导出 - 管理层/财务/法务 */
    @GetMapping("/export/excel")
    public void exportExcel(@RequestParam(required = false) String settlementMonth,
                            HttpServletResponse response) throws IOException {
        if (!accessUtil.canView()) { response.sendError(403, "无权限导出红人结款"); return; }
        List<InfluencerPayment> payments = settlementMonth != null && !settlementMonth.isEmpty()
                ? paymentRepo.findBySettlementMonthAndIsDeletedFalse(settlementMonth)
                : paymentRepo.findByIsDeletedFalse();
        paymentService.attachTeamIds(payments);
        excelHandler.export(payments, response);
    }

    /** 新建/编辑合并入口（跟其它模块一致：req.id 是否为空决定走新建还是编辑） */
    @PostMapping
    public ApiResponse<InfluencerPayment> save(@Valid @RequestBody InfluencerPaymentRequest req) {
        if (!accessUtil.canManage()) return ApiResponse.error(403, req.getId() == null ? "无权限新增红人结款记录" : "无权限编辑红人结款记录");
        if (req.getId() == null) return ApiResponse.success(paymentService.create(req));
        return ApiResponse.success(paymentService.update(req.getId(), req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        if (!accessUtil.canManage()) return ApiResponse.error(403, "无权限删除红人结款记录");
        paymentService.delete(id);
        return ApiResponse.success();
    }

    /** 状态流转：只改付款状态 + 实际付款日，不接收也不会改动其他任何字段 */
    @PatchMapping("/{id}/status")
    public ApiResponse<InfluencerPayment> updateStatus(
            @PathVariable Long id, @RequestBody InfluencerPaymentStatusRequest req) {
        if (!accessUtil.canManage()) return ApiResponse.error(403, "无权限修改红人结款状态");
        return ApiResponse.success(paymentService.updateStatus(id, req));
    }

    private Date parseDate(String str) {
        if (str == null || str.isEmpty()) return null;
        try { return new SimpleDateFormat("yyyy-MM-dd").parse(str); }
        catch (ParseException e) { return null; }
    }
}
