package com.lusuoria.settlement.config;

import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.repository.EmployeeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 员工信息内存缓存
 * 启动时加载，每4小时自动刷新，也可以主动调用 refresh() 立即更新
 */
@Component
public class EmployeeCache {

    @Autowired private EmployeeRepository employeeRepo;

    // key: 员工姓名（name），value: Employee 对象
    private volatile Map<String, Employee> nameMap = new ConcurrentHashMap<String, Employee>();
    // key: 员工 id，value: Employee 对象
    private volatile Map<Long, Employee>   idMap   = new ConcurrentHashMap<Long, Employee>();

    @PostConstruct
    public void init() {
        refresh();
    }

    /** 每4小时自动刷新一次 */
    @Scheduled(fixedDelay = 4 * 60 * 60 * 1000)
    public synchronized void refresh() {
        List<Employee> all = employeeRepo.findByIsDeletedFalseOrderByNameAsc();
        Map<String, Employee> nm = new ConcurrentHashMap<String, Employee>();
        Map<Long, Employee>   im = new ConcurrentHashMap<Long, Employee>();
        for (Employee e : all) {
            if (e.getName() != null) nm.put(e.getName().trim(), e);
            im.put(e.getId(), e);
        }
        nameMap = nm;
        idMap   = im;
    }

    /** 按姓名查找员工（用于 Excel 导入跟进人字段匹配） */
    public Employee findByName(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        return nameMap.get(name.trim());
    }

    /** 按 id 查找员工 */
    public Employee findById(Long id) {
        if (id == null) return null;
        return idMap.get(id);
    }

    /** 获取所有员工列表（按姓名排序） */
    public List<Employee> getAll() {
        return new java.util.ArrayList<Employee>(nameMap.values());
    }

    /**
     * 返回角色="管理层"的唯一一条员工记录（目前系统里只有一个人是管理层），没有则返回 null。
     * 供 ADMIN 在"员工管理"页面配置执行人员薪资梯度时使用——ADMIN 本身不一定关联了员工记录，
     * 这种场景下本质是代表这唯一的管理层在维护 ExecutorPayRate。
     */
    public Employee findManagementEmployee() {
        for (Employee e : nameMap.values()) {
            if ("管理层".equals(e.getRole())) return e;
        }
        return null;
    }
}
