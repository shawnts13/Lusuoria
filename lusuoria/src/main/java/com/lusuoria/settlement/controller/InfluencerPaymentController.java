package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.dto.request.InfluencerPaymentRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.entity.InfluencerPayment;
import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.entity.ProjectOrder;
import com.lusuoria.settlement.enums.InfluencerPaymentStatus;
import com.lusuoria.settlement.excel.InfluencerPaymentExcelHandler;
import com.lusuoria.settlement.repository.InfluencerPaymentRepository;
import com.lusuoria.settlement.repository.InfluencerRepository;
import com.lusuoria.settlement.repository.ProjectOrderRepository;
import com.lusuoria.settlement.util.RoleUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

@RestController
@RequestMapping("/api/influencer-payments")
public class InfluencerPaymentController {

    @Autowired private InfluencerPaymentRepository paymentRepo;
    @Autowired private InfluencerRepository influencerRepo;
    @Autowired private ProjectOrderRepository projectOrderRepo;
    @Autowired private InfluencerPaymentExcelHandler excelHandler;

    @GetMapping
    public ApiResponse<Page<InfluencerPayment>> list(
            @RequestParam(required = false) String settlementMonth,
            @RequestParam(required = false) Long influencerId,
            @RequestParam(required = false) InfluencerPaymentStatus paymentStatus,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "settlementMonth"));
        return ApiResponse.success(paymentRepo.findByFilters(settlementMonth, influencerId, paymentStatus, pageable));
    }

    @GetMapping("/{id}")
    public ApiResponse<InfluencerPayment> getById(@PathVariable Long id) {
        return ApiResponse.success(paymentRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("结款记录不存在")));
    }

    /** 导出 - 所有角色，金额字段按角色脱敏 */
    @GetMapping("/export/excel")
    public void exportExcel(@RequestParam(required = false) String settlementMonth,
                            HttpServletResponse response) throws IOException {
        List<InfluencerPayment> payments = settlementMonth != null && !settlementMonth.isEmpty()
                ? paymentRepo.findBySettlementMonthAndIsDeletedFalse(settlementMonth)
                : paymentRepo.findAll();
        excelHandler.export(payments, RoleUtil.canViewSensitiveFields(), response);
    }

    /** 下载模板 - 所有角色，金额列按权限决定 */
    @GetMapping("/import/template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        excelHandler.downloadTemplate(RoleUtil.canViewSensitiveFields(), response);
    }

    /** 导入 - ADMIN 和 STAFF */
    @PostMapping("/import/excel")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<List<String>> importExcel(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) return ApiResponse.error(400, "请选择要上传的文件");
        String fn = file.getOriginalFilename();
        if (fn == null || (!fn.endsWith(".xlsx") && !fn.endsWith(".xls")))
            return ApiResponse.error(400, "只支持 .xlsx 或 .xls 格式");
        return ApiResponse.success(excelHandler.importData(file));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<InfluencerPayment> save(@Valid @RequestBody InfluencerPaymentRequest req) {
        InfluencerPayment payment;
        if (req.getId() != null) {
            payment = paymentRepo.findByIdAndIsDeletedFalse(req.getId())
                    .orElseThrow(() -> new RuntimeException("结款记录不存在"));
        } else {
            payment = new InfluencerPayment();
            payment.setIsDeleted(false);
            String payNo = "PAY-" + req.getSettlementMonth() + "-"
                    + new SimpleDateFormat("HHmmss").format(new Date());
            payment.setPaymentNo(payNo);
        }
        payment.setSettlementMonth(req.getSettlementMonth());
        Influencer influencer = influencerRepo.findByIdAndIsDeletedFalse(req.getInfluencerId())
                .orElseThrow(() -> new RuntimeException("红人不存在"));
        payment.setInfluencer(influencer);
        if (req.getProjectOrderId() != null) {
            ProjectOrder order = projectOrderRepo.findByIdAndIsDeletedFalse(req.getProjectOrderId())
                    .orElseThrow(() -> new RuntimeException("项目订单不存在"));
            payment.setProjectOrder(order);
        }
        payment.setCooperationContent(req.getCooperationContent());
        payment.setCooperationQuantity(req.getCooperationQuantity());
        payment.setInfluencerUnitPrice(req.getInfluencerUnitPrice());
        payment.setPayableAmount(req.getPayableAmount());
        payment.setCurrency(req.getCurrency());
        payment.setExchangeRate(req.getExchangeRate());
        payment.setRmbAmount(req.getRmbAmount());
        payment.setReconcileDate(req.getReconcileDate());
        payment.setExpectedPaymentDate(req.getExpectedPaymentDate());
        payment.setActualPaymentDate(req.getActualPaymentDate());
        if (req.getPaymentStatus() != null) payment.setPaymentStatus(req.getPaymentStatus());
        payment.setPaidAmount(req.getPaidAmount());
        payment.setNotes(req.getNotes());
        return ApiResponse.success(paymentRepo.save(payment));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        InfluencerPayment payment = paymentRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("结款记录不存在"));
        payment.setIsDeleted(true);
        paymentRepo.save(payment);
        return ApiResponse.success();
    }
}
