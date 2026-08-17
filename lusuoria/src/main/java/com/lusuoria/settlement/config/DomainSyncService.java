package com.lusuoria.settlement.config;

import com.lusuoria.settlement.entity.Domain;
import com.lusuoria.settlement.repository.DomainRepository;
import com.lusuoria.settlement.repository.InfluencerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 领域同步服务
 * 根据 influencers 表里实际使用的领域，同步更新 domains 表
 * 没有任何红人使用的领域会被软删除
 */
@Service
public class DomainSyncService {

    @Autowired private DomainRepository domainRepo;
    @Autowired private InfluencerRepository influencerRepo;
    @Autowired private DomainCache domainCache;

    public void sync() {
        // 收集所有红人实际使用的领域名称
        // 2026-08-13 性能修复：改用只查 domains 一列的轻量投影（findActiveDomainsRaw），
        // 不再用 findByIsDeletedFalseOrderByAccountNameAsc() 加载全量实体（notes/联系方式/
        // 成本字段等一起查出来，这里根本用不上）——见该方法注释。
        Set<String> usedDomains = new HashSet<String>();
        for (String domains : influencerRepo.findActiveDomainsRaw()) {
            if (domains != null && !domains.trim().isEmpty()) {
                for (String d : domains.split("[\n,]+")) {
                    String dn = d.trim();
                    if (!dn.isEmpty()) usedDomains.add(dn);
                }
            }
        }

        // 软删除没有被使用的领域
        // 2026-08-17 性能修复：原来每个要软删的 domain 单独调一次 domainRepo.save(domain)（旧代码
        // 保留在下面注释里），改成收集到 list 里最后统一 saveAll() 一次。领域是一张小的、人工
        // 维护的分类表，单次 sync() 里真正命中软删条件的行数通常很少，实际影响不大，顺手改掉。
        List<Domain> allDomains = domainRepo.findByIsDeletedFalseOrderByNameAsc();
        boolean changed = false;
        List<Domain> toSoftDelete = new ArrayList<Domain>();
        for (Domain domain : allDomains) {
            if (!usedDomains.contains(domain.getName())) {
                domain.setIsDeleted(true);
                toSoftDelete.add(domain);
                changed = true;
                /* ===== 旧代码：domainRepo.save(domain); （2026-08-17 停用，改成统一 saveAll，
                 * 按 Shawn 要求保留对比，不要直接删）===== */
            }
        }
        if (!toSoftDelete.isEmpty()) domainRepo.saveAll(toSoftDelete);

        // 确保所有使用中的领域都在表里
        for (String name : usedDomains) {
            if (domainCache.findByName(name) == null) {
                domainCache.getOrCreate(name);
                changed = true;
            }
        }

        if (changed) domainCache.refresh();
    }
}
