package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.config.InfluencerTeamCache;
import com.lusuoria.settlement.dto.request.BrandRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.dto.response.BrandTeamOption;
import com.lusuoria.settlement.entity.Brand;
import com.lusuoria.settlement.entity.InfluencerTeam;
import com.lusuoria.settlement.excel.BrandExcelHandler;
import com.lusuoria.settlement.repository.BrandRepository;
import com.lusuoria.settlement.repository.InfluencerBrandTeamRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/brands")
public class BrandController {

    @Autowired private BrandRepository brandRepo;
    @Autowired private BrandExcelHandler excelHandler;
    @Autowired private BrandCache brandCache;
    @Autowired private InfluencerBrandTeamRepository influencerBrandTeamRepo;
    @Autowired private InfluencerTeamCache teamCache;

    @GetMapping
    public ApiResponse<List<Brand>> list() {
        return ApiResponse.success(brandCache.getAll());
    }

    /**
     * 该品牌方下（不限具体红人）出现过的团队选项，供"红人结款"新建结款记录时
     * "先选品牌方，再选该品牌方下的红人团队"级联选择用。
     */
    @GetMapping("/{id}/team-options")
    public ApiResponse<List<BrandTeamOption>> teamOptions(@PathVariable Long id) {
        List<BrandTeamOption> result = new ArrayList<>();
        boolean hasNoTeamOption = false;
        for (Long teamId : influencerBrandTeamRepo.findDistinctTeamIdsByBrandId(id)) {
            if (teamId == null) {
                hasNoTeamOption = true;
                continue;
            }
            InfluencerTeam team = teamCache.findById(teamId);
            if (team != null) result.add(new BrandTeamOption(team.getId(), team.getName()));
        }
        result.sort((a, b) -> a.getTeamName().compareTo(b.getTeamName()));
        if (hasNoTeamOption) result.add(new BrandTeamOption(null, null));
        return ApiResponse.success(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<Brand> getById(@PathVariable Long id) {
        Brand brand = brandCache.findById(id);
        if (brand == null) throw new RuntimeException("品牌方不存在");
        return ApiResponse.success(brand);
    }

    @GetMapping("/export/excel")
    public void exportExcel(HttpServletResponse response) throws IOException {
        excelHandler.export(brandCache.getAll(), response);
    }

    @GetMapping("/import/template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        excelHandler.downloadTemplate(response);
    }

    @PostMapping("/import/excel")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<List<String>> importExcel(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) return ApiResponse.error(400, "请选择要上传的文件");
        String fn = file.getOriginalFilename();
        if (fn == null || (!fn.endsWith(".xlsx") && !fn.endsWith(".xls")))
            return ApiResponse.error(400, "只支持 .xlsx 或 .xls 格式");
        List<String> result = excelHandler.importData(file);
        brandCache.refresh();
        return ApiResponse.success(result);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<Brand> save(@Valid @RequestBody BrandRequest req) {
        Brand brand;
        if (req.getId() != null) {
            brand = brandRepo.findByIdAndIsDeletedFalse(req.getId())
                    .orElseThrow(() -> new RuntimeException("品牌方不存在"));
        } else {
            if (brandRepo.existsByNameAndIsDeletedFalse(req.getName()))
                throw new RuntimeException("品牌方名称已存在：" + req.getName());
            brand = new Brand();
            brand.setIsDeleted(false);
        }
        brand.setName(req.getName());
        brand.setCountryMarket(req.getCountryMarket());
        brand.setCooperationType(req.getCooperationType());
        brand.setContactPerson(req.getContactPerson());
        brand.setSettlementCurrency(req.getSettlementCurrency());
        brand.setPaymentCycleType(req.getPaymentCycleType());
        brand.setCostThresholdAmount(req.getCostThresholdAmount());
        brand.setDaysWithinThreshold(req.getDaysWithinThreshold());
        brand.setDaysAboveThreshold(req.getDaysAboveThreshold());
        brand.setDaysAfterMonthEnd(req.getDaysAfterMonthEnd());
        brand.setNotes(req.getNotes());
        Brand saved = brandRepo.save(brand);
        brandCache.refresh();
        return ApiResponse.success(saved);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        Brand brand = brandRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("品牌方不存在"));
        brand.setIsDeleted(true);
        brandRepo.save(brand);
        brandCache.refresh();
        return ApiResponse.success();
    }
}
