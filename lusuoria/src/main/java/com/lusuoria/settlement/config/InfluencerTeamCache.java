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

    /**
     * 按名称获取或创建团队（保存红人时自动注册新团队名）
     */
    public InfluencerTeam getOrCreate(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        InfluencerTeam existing = findByName(name.trim());
        if (existing != null) return existing;
        InfluencerTeam team = new InfluencerTeam();
        team.setName(name.trim());
        team.setIsDeleted(false);
        InfluencerTeam saved = teamRepo.save(team);
        refresh();
        return saved;
    }
}
