package com.lusuoria.settlement.config;

import com.lusuoria.settlement.entity.EmployeeManagedBrand;
import com.lusuoria.settlement.repository.EmployeeManagedBrandRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "项目管理员负责哪些品牌方"内存缓存（2026-08-21 新增，"项目管理员"角色需求的一部分）。
 *
 * 读多写少：改动只发生在员工管理编辑"项目管理员"时手动调整品牌方列表，但会被待处理审核/
 * 进度提醒（判断某条记录的品牌方是不是当前项目管理员负责的范围）在几乎每次列表加载时
 * 高频读取，符合这套 *Cache 系列一直在处理的场景，做法照抄 ExecutorPayRateTierCache：
 * 启动时加载，每4小时自动刷新，EmployeeController 写入后主动调用 refresh()。
 *
 * 双向索引：byEmployeeId 供"员工管理"表单回显用；byBrandId 供提醒/待处理反查"这个品牌方
 * 现在归哪些项目管理员负责"用（同一个品牌方理论上可以被多个项目管理员同时负责，虽然目前
 * 前端只会让一个品牌方对应一个项目管理员，缓存本身不假设这个业务约束，按多对多存）。
 */
@Component
public class EmployeeManagedBrandCache {

    @Autowired private EmployeeManagedBrandRepository repo;

    private volatile Map<Long, Set<Long>> byEmployeeId = new ConcurrentHashMap<>();
    private volatile Map<Long, Set<Long>> byBrandId = new ConcurrentHashMap<>();

    /** Bean 构造完成后首次加载 */
    @PostConstruct
    public void init() { refresh(); }

    /** 全量重查一遍未软删的关联，按员工/品牌方两个方向各建一份索引 */
    @Scheduled(fixedDelay = 4 * 60 * 60 * 1000)
    public synchronized void refresh() {
        Map<Long, Set<Long>> empMap = new ConcurrentHashMap<>();
        Map<Long, Set<Long>> brandMap = new ConcurrentHashMap<>();
        for (EmployeeManagedBrand m : repo.findByIsDeletedFalse()) {
            empMap.computeIfAbsent(m.getEmployeeId(), k -> ConcurrentHashMap.newKeySet()).add(m.getBrandId());
            brandMap.computeIfAbsent(m.getBrandId(), k -> ConcurrentHashMap.newKeySet()).add(m.getEmployeeId());
        }
        byEmployeeId = empMap;
        byBrandId = brandMap;
    }

    /** 某个项目管理员负责的品牌方 id 集合，没配置过返回空集合（不是 null） */
    public Set<Long> findBrandIdsByEmployeeId(Long employeeId) {
        if (employeeId == null) return Collections.emptySet();
        return new HashSet<>(byEmployeeId.getOrDefault(employeeId, Collections.emptySet()));
    }

    /** 某个品牌方现在归哪些项目管理员负责（员工 id 集合），没人负责返回空集合 */
    public Set<Long> findEmployeeIdsByBrandId(Long brandId) {
        if (brandId == null) return Collections.emptySet();
        return new HashSet<>(byBrandId.getOrDefault(brandId, Collections.emptySet()));
    }

    /** 某个项目管理员是否负责这个品牌方，供权限/可见性判断直接调用，语义比自己拼 contains 更直观 */
    public boolean manages(Long employeeId, Long brandId) {
        if (employeeId == null || brandId == null) return false;
        return byEmployeeId.getOrDefault(employeeId, Collections.emptySet()).contains(brandId);
    }
}
