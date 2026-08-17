package com.lusuoria.settlement.config;

import com.lusuoria.settlement.entity.SysUser;
import com.lusuoria.settlement.repository.SysUserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录账号（SysUser）内存缓存（2026-08-17 新增）。
 *
 * 只覆盖"只读查当前登录人是谁"这一类场景——{@link com.lusuoria.settlement.util.EmployeeRoleUtil}/
 * {@link com.lusuoria.settlement.util.ProjectFieldVisibility} 几乎每个请求都要按用户名查一次
 * SysUser 来拿 employeeId，同一个请求内往往被这两个类各自独立查好几遍（比如红人合作跟踪列表页
 * 一次请求就查 3~4 次，都是同一行）。新建/编辑/启用禁用/改密码/删除账号这些写操作，以及登录时
 * Spring Security 自己的 UserDetailsServiceImpl.loadUserByUsername()（校验密码/enabled 的地方），
 * 仍然直接查 {@link SysUserRepository}，不受这个缓存影响——写操作本来就需要一个能 save() 的活
 * 对象，登录校验更是必须读当下最新的密码/enabled 状态，这两类场景从设计上就不应该走缓存。
 *
 * 启动时加载，每4小时自动刷新，{@link com.lusuoria.settlement.controller.UserController} 的
 * 新建/编辑/启用禁用/改密码/删除，以及
 * {@link com.lusuoria.settlement.service.impl.ProgressReminderService#markPopupSeen()}
 * （更新"最后看到提醒弹窗"的时间戳）写完后都会主动调用 refresh()。
 */
@Component
public class SysUserCache {

    @Autowired private SysUserRepository sysUserRepo;

    // key: 用户名，value: SysUser——只装未软删除的账号，语义对齐 findByUsernameAndIsDeletedFalse
    private volatile Map<String, SysUser> byUsername = new ConcurrentHashMap<>();

    /** Bean 构造完成后首次加载 */
    @PostConstruct
    public void init() { refresh(); }

    /** 全量重查一遍未软删的账号，整体替换 map */
    @Scheduled(fixedDelay = 4 * 60 * 60 * 1000)
    public synchronized void refresh() {
        Map<String, SysUser> map = new ConcurrentHashMap<>();
        for (SysUser u : sysUserRepo.findByIsDeletedFalseOrderByUsernameAsc()) {
            map.put(u.getUsername(), u);
        }
        byUsername = map;
    }

    /** 按用户名查（未软删除的账号），对应 findByUsernameAndIsDeletedFalse；没有则返回 null */
    public SysUser findByUsername(String username) {
        if (username == null) return null;
        return byUsername.get(username);
    }

    /** 全部未软删除的账号，按用户名升序（"账号管理"列表页用），防御性拷贝 */
    public List<SysUser> getAll() {
        List<SysUser> list = new ArrayList<>(byUsername.values());
        list.sort(Comparator.comparing(SysUser::getUsername));
        return list;
    }
}
