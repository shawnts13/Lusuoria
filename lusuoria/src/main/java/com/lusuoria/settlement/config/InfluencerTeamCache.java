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

    /**
     * 按名称获取或创建团队（保存红人时自动注册新团队名）
     */
    public synchronized InfluencerTeam getOrCreate(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        String trimmed = name.trim();
        InfluencerTeam existing = findByName(trimmed);
        if (existing != null) return existing;

        // 缓存里没有，但数据库可能有软删除的同名记录，复活而非新插入，避免唯一约束冲突
        InfluencerTeam team = teamRepo.findByName(trimmed).orElse(null);
        if (team != null) {
            if (Boolean.TRUE.equals(team.getIsDeleted())) {
                team.setIsDeleted(false);
                team = teamRepo.save(team);
            }
        } else {
            team = new InfluencerTeam();
            team.setName(trimmed);
            team.setIsDeleted(false);
            team = teamRepo.save(team);
        }
        refresh();
        return team;
    }
}
