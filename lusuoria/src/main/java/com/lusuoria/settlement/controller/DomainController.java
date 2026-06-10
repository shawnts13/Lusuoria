package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.config.DomainCache;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.entity.Domain;
import com.lusuoria.settlement.repository.DomainRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/domains")
public class DomainController {

    @Autowired private DomainRepository domainRepo;
    @Autowired private DomainCache domainCache;

    /** 获取所有领域，按名称排序（前端下拉用） */
    @GetMapping
    public ApiResponse<List<Domain>> list() {
        List<Domain> all = domainCache.getAll();
        all.sort(java.util.Comparator.comparing(Domain::getName));
        return ApiResponse.success(all);
    }

    /** 新增领域（在红人编辑表单里新增） */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<Domain> add(@RequestBody String name) {
        name = name.trim().replaceAll("^\"|\"$", "");  // 去掉可能的引号
        if (name.isEmpty()) throw new RuntimeException("领域名称不能为空");
        if (domainRepo.existsByNameAndIsDeletedFalse(name))
            throw new RuntimeException("领域已存在：" + name);
        Domain domain = new Domain();
        domain.setName(name);
        domain.setIsDeleted(false);
        Domain saved = domainRepo.save(domain);
        domainCache.refresh();
        return ApiResponse.success(saved);
    }

    /** 删除领域（仅 ADMIN） */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        Domain domain = domainRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("领域不存在"));
        domain.setIsDeleted(true);
        domainRepo.save(domain);
        domainCache.refresh();
        return ApiResponse.success();
    }
}
