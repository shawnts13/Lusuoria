package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.ProgressReminder;
import com.lusuoria.settlement.enums.ReminderCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * 【Spring Data JPA 知识点】这是一个 interface，没有写任何实现类，但 Spring Data JPA 在启动时会
 * 自动帮它生成一个动态代理实现类扔进容器——这就是为什么全项目的 Repository 都只声明方法签名、
 * 从来不用手写 SQL/实现体。基础的增删改查（save/findById/findAll/deleteById...）来自继承的
 * JpaRepository<ProgressReminder, Long>（两个泛型参数分别是"实体类型"和"主键类型"）；下面这些
 * findByXxx/deleteByXxx 方法则是"派生查询方法"（derived query method）——Spring Data 会按照
 * 固定规则解析方法名本身来生成查询，不需要写实际 SQL/JPQL：
 *   findBy + 字段名（驼峰对应实体的属性名，不是数据库列名）→ WHERE 该字段 = 参数
 *   多个条件用 And/Or 连接，比如 findByCategoryAndAudienceEmployeeId
 *   参数是 Collection 时配合 In 后缀（如 findByCategoryIn）→ WHERE 字段 IN (...)
 * 这套"方法名即查询"的写法足够覆盖大多数简单场景；复杂查询（多表关联、动态条件拼接）就要用
 * 下面 deleteByCategoryIn 这种手写 @Query 的方式，或者用 JpaSpecificationExecutor。
 */
@Repository
public interface ProgressReminderRepository extends JpaRepository<ProgressReminder, Long> {

    /**
     * 表里任何时刻都只有"最新一次跑批"的结果，按受众角色查即可，不需要按 batchDate 过滤。
     * 不在这里排序：urgency/category 是 EnumType.STRING，数据库按字母序排跟业务期望的
     * "已超期 -> 1-3天 -> 3-7天"顺序对不上，排序统一交给 Service 层按枚举 ordinal 处理。
     */
    List<ProgressReminder> findByAudienceEmployeeRole(String audienceEmployeeRole);

    /** 2026-07 新增：按具体员工定向的提醒（PM_EXECUTOR_PROGRESS_STALL/REQUIREMENT_INVOICE_OVERDUE） */
    List<ProgressReminder> findByAudienceEmployeeId(Long audienceEmployeeId);

    /** 管理层/ADMIN 的"全部可见"查询：不按受众过滤，返回全表（表本身就是"最新一次跑批"的全量结果） */
    List<ProgressReminder> findAllByIsDeletedFalse();

    /** 2026-07 新增：手动"分类重算"用——只清空/重算指定几类，不影响其它类别当天已经算好的数据 */
    List<ProgressReminder> findByCategoryIn(Collection<ReminderCategory> categories);

    /**
     * 2026-08 修复：原来是 Spring Data 派生的 deleteByCategoryIn（逐条实体删除），并发重算时
     * 会因为"这一行已经被另一个事务删了"抛 StaleStateException，见
     * ProgressReminderDetailRepository.deleteByReminderIdIn 的同款修复注释。改成批量 JPQL
     * DELETE，幂等，不再受并发影响。
     *
     * 【Spring Data JPA 知识点】"派生查询方法"里的 deleteByXxx 跟 findByXxx 走的是两条完全不同的
     * 路：deleteByXxx 底层其实是"先 SELECT 出所有匹配的实体、加载进持久化上下文，再对每一条逐个
     * 调用 entityManager.remove()"——本质是一条条删，只是省了手写循环；这在并发场景下就是上面
     * 注释说的问题根源：两个事务前后脚都查到了同一批"待删除"的行，其中一个删完之后，另一个再对
     * 这些已经不存在的行调用 remove() 时，Hibernate 发现"要更新/删除的这一行在数据库里已经不是
     * 我加载时的版本了"，抛出 StaleStateException（乐观锁冲突的一种体现）。
     * 这里改用 @Modifying + @Query 手写 JPQL（JPQL 是"面向实体/字段名"的类 SQL 语法，不是
     * 直接写数据库表名列名，实体名 ProgressReminder、字段名 category 用的是 Java 端的名字）
     * 直接生成一条 "DELETE FROM 表 WHERE ..." 语句发给数据库一次性执行，不经过"先查出来再逐条删"
     * 这一步，天然没有上面这个并发问题。@Modifying 是必须加的：默认情况下 @Query 只能用来查询，
     * 加了 @Modifying 才允许这条 @Query 是 INSERT/UPDATE/DELETE 语句；这类"修改型"查询还要求
     * 调用方在一个已经开启的事务里执行（对应 Service 层方法上的 @Transactional），不能像普通
     * findXxx 那样脱离事务直接调用，否则 Spring 会在运行时直接抛异常拒绝执行。
     */
    @Modifying
    @Query("DELETE FROM ProgressReminder r WHERE r.category IN :categories")
    void deleteByCategoryIn(@Param("categories") Collection<ReminderCategory> categories);
}
