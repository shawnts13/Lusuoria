package com.lusuoria.settlement.config;

import com.lusuoria.settlement.entity.Domain;
import com.lusuoria.settlement.repository.DomainRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 领域内存缓存
 * 启动时加载，每4小时自动刷新
 * 新增领域后主动调用 refresh()
 */
@Component
public class DomainCache {

    @Autowired private DomainRepository domainRepo;

    private volatile Map<String, Domain> nameMap = new ConcurrentHashMap<String, Domain>();
    private volatile Map<Long, Domain>   idMap   = new ConcurrentHashMap<Long, Domain>();

    @PostConstruct
    public void init() { refresh(); }

    @Scheduled(fixedDelay = 4 * 60 * 60 * 1000)
    public synchronized void refresh() {
        Map<String, Domain> nm = new ConcurrentHashMap<String, Domain>();
        Map<Long, Domain>   im = new ConcurrentHashMap<Long, Domain>();
        domainRepo.findByIsDeletedFalseOrderByNameAsc().forEach(d -> {
            nm.put(d.getName().trim(), d);
            im.put(d.getId(), d);
        });
        nameMap = nm;
        idMap   = im;
    }

    public Domain findByName(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        return nameMap.get(name.trim());
    }

    public List<Domain> getAll() {
        return new java.util.ArrayList<Domain>(nameMap.values());
    }

    /**
     * 按名称获取或创建领域（Excel 导入时使用）
     * 如果领域不存在则插入数据库并刷新缓存
     */
    public Domain getOrCreate(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        Domain existing = findByName(name.trim());
        if (existing != null) return existing;
        Domain domain = new Domain();
        domain.setName(name.trim());
        domain.setIsDeleted(false);
        Domain saved = domainRepo.save(domain);
        refresh();
        return saved;
    }
}
