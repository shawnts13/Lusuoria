package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.dto.request.BrandRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.entity.Brand;
import com.lusuoria.settlement.excel.BrandExcelHandler;
import com.lusuoria.settlement.repository.BrandRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/brands")
public class BrandController {

    @Autowired private BrandRepository brandRepo;
    @Autowired private BrandExcelHandler excelHandler;

    @GetMapping
    public ApiResponse<List<Brand>> list() {
        return ApiResponse.success(brandRepo.findByIsDeletedFalseOrderByNameAsc());
    }

    @GetMapping("/{id}")
    public ApiResponse<Brand> getById(@PathVariable Long id) {
        return ApiResponse.success(brandRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("品牌方不存在")));
    }

    /** 导出 - 所有角色（品牌方无敏感字段）*/
    @GetMapping("/export/excel")
    public void exportExcel(HttpServletResponse response) throws IOException {
        excelHandler.export(brandRepo.findByIsDeletedFalseOrderByNameAsc(), response);
    }

    /** 下载模板 - 所有角色 */
    @GetMapping("/import/template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        excelHandler.downloadTemplate(response);
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
        brand.setPaymentCycle(req.getPaymentCycle());
        brand.setNotes(req.getNotes());
        return ApiResponse.success(brandRepo.save(brand));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        Brand brand = brandRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("品牌方不存在"));
        brand.setIsDeleted(true);
        brandRepo.save(brand);
        return ApiResponse.success();
    }
}
