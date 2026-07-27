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

    @PostConstruct
    public void init() { refresh(); }

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

    public List<InfluencerTeam> getAll() {
        return new java.util.ArrayList<>(nameMap.values());
    }

    public InfluencerTeam findByName(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        return nameMap.get(name.trim());
    }

    public InfluencerTeam findById(Long id) {
        if (id == null) return null;
        return idMap.get(id);
    }

    public List<InfluencerTeam> findByBrandId(Long brandId) {
        if (brandId == null) return java.util.Collections.emptyList();
        List<InfluencerTeam> result = new java.util.ArrayList<>();
        for (InfluencerTeam t : nameMap.values()) {
            if (brandId.equals(t.getBrandId())) result.add(t);
        }
        result.sort(java.util.Comparator.comparing(InfluencerTeam::getName));
        return result;
    }

    /**
     * 按名称获取或创建团队（Excel 导入红人时，"品牌方/团队"这一列自动注册新团队名）。
     * 2026-07 起团队归属唯一品牌方：如果同名团队已经存在但归属的是另一个品牌方，说明团队名称
     * 冲突（不允许跨品牌方复用同一个团队名），抛出异常由 Excel 导入的行级错误处理捕获。
     */
    public synchronized InfluencerTeam getOrCreate(String name, Long brandId) {
        if (name == null || name.trim().isEmpty()) return null;
        String trimmed = name.trim();
        InfluencerTeam existing = findByName(trimmed);
        if (existing != null) {
            if (brandId != null && !brandId.equals(existing.getBrandId())) {
                throw new RuntimeException("团队 [" + trimmed + "] 已属于其他品牌方，请使用其他团队名称，"
                        + "或在“品牌方/红人团队管理”中调整该团队的归属品牌方");
            }
            return existing;
        }

        // 缓存里没有，但数据库可能有软删除的同名记录，复活而非新插入，避免唯一约束冲突
        InfluencerTeam team = teamRepo.findByName(trimmed).orElse(null);
        if (team != null) {
            if (Boolean.TRUE.equals(team.getIsDeleted())) {
                team.setIsDeleted(false);
                team.setBrandId(brandId);
                team = teamRepo.save(team);
            } else if (brandId != null && !brandId.equals(team.getBrandId())) {
                throw new RuntimeException("团队 [" + trimmed + "] 已属于其他品牌方，请使用其他团队名称，"
                        + "或在“品牌方/红人团队管理”中调整该团队的归属品牌方");
            }
        } else {
            team = new InfluencerTeam();
            team.setName(trimmed);
            team.setIsDeleted(false);
            team.setBrandId(brandId);
            team = teamRepo.save(team);
        }
        refresh();
        return team;
    }
}
