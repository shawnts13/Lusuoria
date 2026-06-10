package com.lusuoria.settlement.config;

import com.lusuoria.settlement.entity.Domain;
import com.lusuoria.settlement.repository.DomainRepository;
import com.lusuoria.settlement.repository.InfluencerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
        Set<String> usedDomains = new HashSet<String>();
        influencerRepo.findByIsDeletedFalseOrderByAccountNameAsc().forEach(inf -> {
            if (inf.getDomains() != null && !inf.getDomains().trim().isEmpty()) {
                for (String d : inf.getDomains().split("[\n,]+")) {
                    String dn = d.trim();
                    if (!dn.isEmpty()) usedDomains.add(dn);
                }
            }
        });

        // 软删除没有被使用的领域
        List<Domain> allDomains = domainRepo.findByIsDeletedFalseOrderByNameAsc();
        boolean changed = false;
        for (Domain domain : allDomains) {
            if (!usedDomains.contains(domain.getName())) {
                domain.setIsDeleted(true);
                domainRepo.save(domain);
                changed = true;
            }
        }

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
