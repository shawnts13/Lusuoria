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

    /** Bean 构造完成后首次加载（这时 @Autowired 字段已经注入好了，见类注释） */
    @PostConstruct
    public void init() { refresh(); }

    /** 全量重新查一遍未软删的品牌方，整体替换 idMap（不是清空再逐条塞），避免并发读到中间态 */
    @Scheduled(fixedDelay = 4 * 60 * 60 * 1000)
    public synchronized void refresh() {
        Map<Long, Brand> map = new ConcurrentHashMap<Long, Brand>();
        brandRepo.findByIsDeletedFalseOrderByNameAsc().forEach(b -> map.put(b.getId(), b));
        idMap = map;
    }

    /** 按 id 查找，查不到（不存在/已软删）返回 null */
    public Brand findById(Long id) {
        if (id == null) return null;
        return idMap.get(id);
    }

    /** 按名称查找（Excel 导入用），名称匹配去除首尾空格 */
    public Brand findByName(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        String trimmed = name.trim();
        for (Brand b : idMap.values()) {
            if (trimmed.equals(b.getName())) return b;
        }
        return null;
    }

    /** 全部未软删的品牌方，防御性拷贝一份新 list 返回 */
    public List<Brand> getAll() {
        return new java.util.ArrayList<Brand>(idMap.values());
    }
}
