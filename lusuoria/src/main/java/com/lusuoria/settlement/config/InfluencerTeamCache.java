package com.lusuoria.settlement.config;

import com.lusuoria.settlement.entity.InfluencerTeam;
import com.lusuoria.settlement.repository.InfluencerTeamRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 红人团队内存缓存
 * 启动时加载，每4小时自动刷新
 * 新增团队后主动调用 refresh()
 */
@Component
public class InfluencerTeamCache {

    @Autowired private InfluencerTeamRepository teamRepo;

    private volatile Map<String, InfluencerTeam> nameMap = new ConcurrentHashMap<>();
    private volatile Map<Long, InfluencerTeam>   idMap   = new ConcurrentHashMap<>();

    /** Bean 构造完成后首次加载 */
    @PostConstruct
    public void init() { refresh(); }

    /** 全量重查一遍未软删的团队，整体替换两份 map */
    @Scheduled(fixedDelay = 4 * 60 * 60 * 1000)
    public synchronized void refresh() {
        Map<String, InfluencerTeam> nm = new ConcurrentHashMap<>();
        Map<Long, InfluencerTeam>   im = new ConcurrentHashMap<>();
        teamRepo.findByIsDeletedFalseOrderByNameAsc().forEach(t -> {
            nm.put(t.getName().trim(), t);
            im.put(t.getId(), t);
        });
        nameMap = nm;
        idMap   = im;
    }

    /** 全部未软删的团队，防御性拷贝一份新 list 返回 */
    public List<InfluencerTeam> getAll() {
        return new java.util.ArrayList<>(nameMap.values());
    }

    /** 按名称查找，查不到返回 null */
    public InfluencerTeam findByName(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        return nameMap.get(name.trim());
    }

    /** 按 id 查找，查不到返回 null */
    public InfluencerTeam findById(Long id) {
        if (id == null) return null;
        return idMap.get(id);
    }

    /** 某个品牌方下的全部团队，按名称升序 */
    public List<InfluencerTeam> findByBrandId(Long brandId) {
        if (brandId == null) return java.util.Collections.emptyList();
        List<InfluencerTeam> result = new java.util.ArrayList<>();
        for (InfluencerTeam t : nameMap.values()) {
            if (brandId.equals(t.getBrandId())) result.add(t);
        }
        result.sort(java.util.Comparator.comparing(InfluencerTeam::getName));
        return result;
    }

    // 2026-08 起团队新建统一收紧到只能由管理层通过 InfluencerTeamController#save 完成——
    // 这里原来有一个 getOrCreate(name, brandId)，专给红人 Excel 导入在遇到未知团队名时
    // 顺手自动建团队用，会绕开上面那道管理层限制（哪怕操作导入的人本身就是管理层，也不该有
    // 两条创建入口）。已删除，InfluencerExcelHandler 现在遇到不存在的团队名会直接报行错误，
    // 让用户先去"品牌方/红人团队管理"里新建。
}
