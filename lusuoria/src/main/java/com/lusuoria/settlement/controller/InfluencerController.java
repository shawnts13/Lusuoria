package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.dto.request.InfluencerRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.enums.ProjectType;
import com.lusuoria.settlement.excel.InfluencerExcelHandler;
import com.lusuoria.settlement.repository.InfluencerRepository;
import com.lusuoria.settlement.util.RoleUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/influencers")
public class InfluencerController {

    @Autowired private InfluencerRepository influencerRepo;
    @Autowired private InfluencerExcelHandler excelHandler;

    @GetMapping
    public ApiResponse<List<Influencer>> list(@RequestParam(required = false) ProjectType type) {
        if (type != null) return ApiResponse.success(influencerRepo.findByInfluencerTypeAndIsDeletedFalse(type));
        return ApiResponse.success(influencerRepo.findByIsDeletedFalseOrderByTeamNameAscAccountNameAsc());
    }

    @GetMapping("/{id}")
    public ApiResponse<Influencer> getById(@PathVariable Long id) {
        return ApiResponse.success(influencerRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("红人不存在")));
    }

    /** 导出 - 所有角色，收款信息按角色脱敏 */
    @GetMapping("/export/excel")
    public void exportExcel(@RequestParam(required = false) ProjectType type,
                            HttpServletResponse response) throws IOException {
        List<Influencer> list = type != null
                ? influencerRepo.findByInfluencerTypeAndIsDeletedFalse(type)
                : influencerRepo.findByIsDeletedFalseOrderByTeamNameAscAccountNameAsc();
        excelHandler.export(list, RoleUtil.canViewSensitiveFields(), response);
    }

    /** 下载模板 - 所有角色，按权限决定是否含收款信息列 */
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
    public ApiResponse<Influencer> save(@Valid @RequestBody InfluencerRequest req) {
        Influencer influencer;
        if (req.getId() != null) {
            influencer = influencerRepo.findByIdAndIsDeletedFalse(req.getId())
                    .orElseThrow(() -> new RuntimeException("红人不存在"));
        } else {
            influencer = new Influencer();
            influencer.setIsDeleted(false);
        }
        influencer.setInfluencerType(req.getInfluencerType());
        influencer.setTeamName(req.getTeamName());
        influencer.setAccountName(req.getAccountName());
        influencer.setCountryMarket(req.getCountryMarket());
        influencer.setPlatform(req.getPlatform());
        influencer.setCooperationMode(req.getCooperationMode());
        influencer.setPaymentInfo(req.getPaymentInfo());
        influencer.setNotes(req.getNotes());
        return ApiResponse.success(influencerRepo.save(influencer));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        Influencer inf = influencerRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("红人不存在"));
        inf.setIsDeleted(true);
        influencerRepo.save(inf);
        return ApiResponse.success();
    }
}
