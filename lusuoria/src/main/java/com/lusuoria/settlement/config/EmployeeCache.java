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
 *
 * 【Spring 知识点】@Component 让 Spring 在启动时做 classpath 扫描时发现这个类，自动 new 一个
 * 实例交给容器管理（默认单例，全应用共用这一个对象）。别的类只要用 @Autowired 声明一个
 * EmployeeCache 类型的字段，Spring 就会把这个唯一实例注入进去——不需要自己 new，也不用担心
 * 拿到的是不是同一份数据，全局就这一份。这种"字段上直接写 @Autowired"是最简单的依赖注入写法，
 * 缺点是没法在字段上加 final、也没法脱离 Spring 容器单独 new 出来做单元测试；构造器注入
 * （参数写在构造方法里）更规范，但这个项目里字段注入是主流写法，跟着现有风格走即可。
 */
@Component
public class EmployeeCache {

    @Autowired private EmployeeRepository employeeRepo;

    // key: 员工姓名（name），value: Employee 对象
    // 用 volatile 修饰是因为 refresh() 是"整个 Map 引用替换"而不是"清空原 Map 再逐条塞回去"——
    // 没有 volatile 的话，别的线程有可能因为 CPU 缓存的关系，refresh() 执行完之后还读到刷新前的
    // 旧引用（可见性问题）。ConcurrentHashMap 本身保证的是"同一个 Map 实例内部并发读写安全"，
    // 不保证"换了一个新 Map 实例之后所有线程立刻看到"，这两件事是分开的，都要处理到。
    private volatile Map<String, Employee> nameMap = new ConcurrentHashMap<String, Employee>();
    // key: 员工 id，value: Employee 对象
    private volatile Map<Long, Employee>   idMap   = new ConcurrentHashMap<Long, Employee>();

    // 【Spring 知识点】@PostConstruct 不是 Spring 自己的注解（来自 javax.annotation，Java EE 标准的
    // 一部分），Spring 只是识别并支持它：等这个 Bean 的构造方法跑完、并且所有 @Autowired 字段
    // （这里就是 employeeRepo）都已经被注入完毕之后，容器会自动调用这个方法一次。用它而不是直接
    // 在构造方法里查库，是因为构造方法执行的时候 employeeRepo 还没被注入（依赖注入发生在构造之后），
    // 这时候调用 employeeRepo 会拿到 null 直接空指针。
    @PostConstruct
    public void init() {
        refresh();
    }

    /**
     * 每4小时自动刷新一次。
     *
     * 【Spring 知识点】@Scheduled(fixedDelay = ...) 由 Spring 内置的任务调度线程池按固定节奏
     * 自动调用这个方法（前提是启动类上有 @EnableScheduling，见 MyApplication）——不需要自己写
     * Timer/while(true)+sleep。fixedDelay 是"上一次执行**结束**之后再等这么久才开始下一次"，
     * 跟 fixedRate（"从上一次**开始**算起，固定周期触发，不等上次执行完"）是两个概念，缓存刷新这种
     * 场景显然应该用 fixedDelay，避免上一次刷新还没跑完，下一次又叠上来。
     *
     * 方法上加 synchronized：防止"定时任务的自动刷新"和"业务代码手动调用 refresh()"（比如
     * Excel 导入完之后主动刷新一次）这两种触发方式并发执行，互相踩踏出现中间状态。
     *
     * 实现上是"造一个全新的 Map，装满数据后再整体替换掉 nameMap/idMap 的引用"，而不是往现有的
     * nameMap 里直接 clear() 再 put()：后者会让并发读到这个 Map 的其他线程，在刷新过程中的某个
     * 瞬间看到"部分员工已经被删掉、新数据还没填完"的残缺状态；前者因为整个替换动作只是一次引用
     * 赋值（对 volatile 字段而言是原子的），其他线程要么看到刷新前的完整旧数据，要么看到刷新后的
     * 完整新数据，不会看到中间态。
     */
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
