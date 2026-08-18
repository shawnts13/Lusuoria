package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.config.DomainCache;
import com.lusuoria.settlement.config.DomainSyncService;
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
    @Autowired private DomainSyncService domainSyncService;

    /**
     * 获取所有领域，按名称排序（前端下拉用）。2026-07 起先跑一次 sync() 再返回——
     * 领域软删除目前只在"新建/编辑/删除红人"这几个写操作里被动触发（见 InfluencerController），
     * 如果没有任何红人写操作在某个领域变成"没有红人再使用"之后发生，这个领域会一直残留在
     * 缓存里、继续出现在筛选下拉框中（选中后查出来是0条，这正是本次要修的问题）。
     * 这里主动同步一次，保证下拉框选项跟"是否真的还有红人在用"保持一致，不用等外部写操作
     * 顺带触发。sync() 本身内部已经判断"没有变化就不刷新缓存"，量级也就是全表红人扫一遍，
     * 挂在这个不算高频的查询接口上可以接受。
     */
    @GetMapping
    public ApiResponse<List<Domain>> list() {
        domainSyncService.sync();
        List<Domain> all = domainCache.getAll();
        all.sort(java.util.Comparator.comparing(Domain::getName));
        return ApiResponse.success(all);
    }

    /**
     * 新增领域（在红人编辑表单里新增）。
     *
     * name 数据库层面有唯一约束，不认软删除——领域被软删除后（比如所有用它的红人都改了领域，
     * DomainSyncService.sync() 会把它标记删除）这个名字仍然被那一行占用着，之前这里只查
     * 未软删除的记录（existsByNameAndIsDeletedFalse），命中软删除的同名记录时会在这一步放行、
     * 真正 insert 时才撞唯一键报错，报出一个用户看不懂的"数据处理失败"（2026-08 修复，
     * DomainCache.getOrCreate() 早就正确处理了这个情况，但那是给 Excel 导入用的，这个
     * HTTP 接口是各表单手动"新增领域"用的独立入口，之前没接上同一套复活逻辑）。
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<Domain> add(@RequestBody String name) {
        name = name.trim().replaceAll("^\"|\"$", "");  // 去掉可能的引号
        if (name.isEmpty()) throw new RuntimeException("领域名称不能为空");
        // 不限 isDeleted 查一次（见上方方法注释——这里是复活逻辑的关键），命中已软删除的同名
        // 记录时复活它，命中未删除的直接拒绝，都查不到才真正新建一条
        Domain domain = domainRepo.findByName(name).orElse(null);
        if (domain != null && Boolean.TRUE.equals(domain.getIsDeleted())) {
            domain.setIsDeleted(false);
        } else if (domain != null) {
            throw new RuntimeException("领域已存在：" + name);
        } else {
            domain = new Domain();
            domain.setName(name);
            domain.setIsDeleted(false);
        }
        Domain saved = domainRepo.save(domain);
        // 写完立刻刷新 DomainCache，不然要等最多4小时定时刷新才会反映到各表单的领域下拉框
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
