package com.lusuoria.settlement.config;

import com.lusuoria.settlement.entity.Brand;
import com.lusuoria.settlement.repository.BrandRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 品牌方内存缓存
 * 启动时加载，每4小时自动刷新，新增/修改/删除后主动调用 refresh()
 */
@Component
public class BrandCache {

    @Autowired private BrandRepository brandRepo;

    private volatile Map<Long, Brand> idMap = new ConcurrentHashMap<Long, Brand>();

    @PostConstruct
    public void init() { refresh(); }

    @Scheduled(fixedDelay = 4 * 60 * 60 * 1000)
    public synchronized void refresh() {
        Map<Long, Brand> map = new ConcurrentHashMap<Long, Brand>();
        brandRepo.findByIsDeletedFalseOrderByNameAsc().forEach(b -> map.put(b.getId(), b));
        idMap = map;
    }

    public Brand findById(Long id) {
        if (id == null) return null;
        return idMap.get(id);
    }

    public List<Brand> getAll() {
        return new java.util.ArrayList<Brand>(idMap.values());
    }
}
