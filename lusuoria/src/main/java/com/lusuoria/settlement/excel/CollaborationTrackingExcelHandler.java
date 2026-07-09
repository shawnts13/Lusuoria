package com.lusuoria.settlement.excel;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.config.InfluencerTeamCache;
import com.lusuoria.settlement.dto.request.CollaborationTrackingRequest;
import com.lusuoria.settlement.entity.Brand;
import com.lusuoria.settlement.entity.CollaborationTracking;
import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.entity.InfluencerTeam;
import com.lusuoria.settlement.entity.InfluencerBrandTeam;
import com.lusuoria.settlement.entity.ImportBatch;
import com.lusuoria.settlement.enums.CollaborationProgress;
import com.lusuoria.settlement.enums.InfluencerPaymentProgress;
import com.lusuoria.settlement.enums.VideoType;
import com.lusuoria.settlement.repository.CollaborationTrackingRepository;
import com.lusuoria.settlement.repository.InfluencerBrandTeamRepository;
import com.lusuoria.settlement.repository.InfluencerRepository;
import com.lusuoria.settlement.service.impl.CollaborationTrackingService;
import com.lusuoria.settlement.util.ProjectNoGenerator;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 红人合作跟踪 - Excel 导入导出
 *
 * 导入时兼容存量表的杂乱列名与"合作资源"内容：
 *   "1 IG+TT" -> Instagram + TikTok
 *   "1IG Reel" / "IG" -> Instagram
 *   "YT" -> YouTube
 */
@Component
public class CollaborationTrackingExcelHandler {

    private static final Logger log = LoggerFactory.getLogger(CollaborationTrackingExcelHandler.class);

    /**
     * 公式求值器：Excel 里像"=400*0.65"这种公式单元格很常见（尤其是金额类字段，
     * 经常是拿汇率或者其他单元格算出来的），不处理的话这类单元格会被 getStrByIdx()
     * 直接判定成"读不出来"返回 null，导致对应字段导入后是空的。
     *
     * 用 ThreadLocal 而不是普通实例字段：这个类是 Spring 单例 Bean，如果多个人同时导入，
     * 普通实例字段会被并发的请求互相覆盖，ThreadLocal 保证每个请求（线程）用的是自己
     * 独立的一份，不会互相干扰。导入开始时设置好，结束后一定要清掉，避免内存泄漏
     * （servlet 容器的工作线程是复用的，不清掉的话下一个请求进来还会读到上一次的引用）。
     */
    private static final ThreadLocal<FormulaEvaluator> FORMULA_EVALUATOR = new ThreadLocal<>();

    @Autowired private CollaborationTrackingRepository trackingRepo;
    @Autowired private InfluencerRepository influencerRepo;
    @Autowired private InfluencerBrandTeamRepository influencerBrandTeamRepo;
    @Autowired private BrandCache brandCache;
    @Autowired private EmployeeCache employeeCache;
    @Autowired private InfluencerTeamCache teamCache;
    @Autowired private CollaborationTrackingService trackingService;
    @Autowired private com.lusuoria.settlement.repository.ImportBatchRepository importBatchRepo;
    @Autowired private ProjectNoGenerator projectNoGenerator;

    /** 导出/模板列顺序 */
    // 列定义：[列名, 是否敏感(1=是), 是否仅导出(1=模板不含)]
    private static final String[][] COLUMNS = {
        {"品牌方",                     "0", "0"},
        {"红人团队",                   "0", "0"},  // 该红人在此品牌方下有多个团队可选时必须填写，否则可留空
        {"服务国家/市场",              "0", "1"},  // 仅导出，模板不含（导入时系统自动填充）
        {"红人社媒完整名字",           "0", "0"},
        {"合作平台",                   "0", "0"},
        {"需求内容(具体产品名)",       "0", "0"},
        {"视频发布链接",               "0", "0"},
        {"视频发布时间",               "0", "0"},  // 原名"发布时间"，导入时仍兼容旧列名"发布时间"
        {"视频项目进度",               "0", "0"},  // 原名"进度"，导入时仍兼容旧列名"进度"
        {"红人结款进度",               "0", "0"},  // 默认空，只有"视频项目进度"达到前置条件才允许设置
        {"项目视频类型",               "0", "0"},
        {"采买旧视频的原链接",         "0", "0"},
        {"项目负责人",                 "0", "0"},
        {"内部执行人员",               "0", "0"},
        {"客户方的项目订单",           "0", "0"},
        {"客户方付款批次",             "0", "0"},
        {"红人视频制作与发布成本（美金）", "1", "0"},
        {"客户合作价格（美金）",       "1", "0"},
        {"备注",                       "0", "0"},
    };

    /** 该列是否仅用于导出（模板跳过） */
    private boolean isExportOnly(String[] col) {
        return col.length > 2 && "1".equals(col[2]);
    }

    private static final String[] PROGRESS_LABELS = {
        "待客户出brief", "合同已发给红人", "红人已下单", "拍摄指导已发给红人",
        "待草稿", "待红人修改", "待发布",
        "已发布（未结算）", "已加入客户未结算列表", "客户已结算", "折损"
    };

    private static final String[] PAYMENT_PROGRESS_LABELS = {
        "待红人发送invoice", "红人已提供invoice", "待结款（不涉及invoice）", "已纳入红人结款批次"
    };

    /** 模板里"红人结款进度"表头的提示语（Excel 原生批注，鼠标悬停可见） */
    private static final String PAYMENT_PROGRESS_HINT =
        "该字段默认为空，且只有当\"视频项目进度\"为\"已发布（未结算）\"、\"已加入客户未结算列表\"、"
        + "\"客户已结算\"时，该字段才会被启用";

    /** 模板里"视频发布时间"表头的提示语（Excel 原生批注，鼠标悬停可见） */
    private static final String PUBLISH_DATE_HINT =
        "只有当\"视频项目进度\"为\"已发布（未结算）\"、\"已加入客户未结算列表\"、\"客户已结算\"时才能填写，"
        + "否则这一行会导入失败";

    /** 模板里两个金额列表头的提示语（Excel 原生批注，鼠标悬停可见） */
    private static final String MONEY_FIELD_HINT =
        "该字段必须是数字，支持公式（只要算出来的结果是数字即可），不支持填写文本备注";

    private static final String[] VIDEO_TYPE_LABELS = {
        "实拍新视频", "实拍新图片", "AI新素材", "旧素材重发"
    };

    // ============ 导出 ============
    public void export(List<CollaborationTracking> list, boolean canViewSensitive,
                       HttpServletResponse response) throws IOException {
        XSSFWorkbook wb = new XSSFWorkbook();
        XSSFSheet sheet = wb.createSheet("红人合作跟踪");
        XSSFCellStyle hdrN = headerStyle(wb, false);
        XSSFCellStyle hdrS = headerStyle(wb, true);
        XSSFCellStyle wrap = wrapStyle(wb);

        // 表头
        Row header = sheet.createRow(0);
        int hc = 0;
        for (String[] col : COLUMNS) {
            if ("1".equals(col[1]) && !canViewSensitive) continue;
            Cell cell = header.createCell(hc++);
            cell.setCellValue(col[0]);
            cell.setCellStyle("1".equals(col[1]) ? hdrS : hdrN);
        }

        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");

        // open-in-view=false，请求结束后 Hibernate 会话即关闭，导出发生在事务外，
        // 不能再访问 t.getInfluencer()/t.getTeam() 这类 LAZY @ManyToOne（会抛 LazyInitializationException，
        // 表现为导出的 xlsx 只有一百多字节、内容其实是错误信息、无法打开）。
        // 改用直读的 id 列 + 缓存/一次性批量查来取名称，不触碰懒加载实体。
        Set<Long> infIds = new HashSet<>();
        for (CollaborationTracking t : list) {
            if (t.getInfluencerId() != null) infIds.add(t.getInfluencerId());
        }
        Map<Long, String> influencerNameById = new HashMap<>();
        if (!infIds.isEmpty()) {
            for (Influencer inf : influencerRepo.findAllById(infIds)) {
                influencerNameById.put(inf.getId(), inf.getAccountName());
            }
        }

        int r = 1;
        for (CollaborationTracking t : list) {
            Row row = sheet.createRow(r++);
            int c = 0;
            Brand brand = brandCache.findById(t.getBrandId());
            setCellStr(row, c++, brand != null ? brand.getName() : "", wrap);
            InfluencerTeam team = t.getTeamId() != null ? teamCache.findById(t.getTeamId()) : null;
            setCellStr(row, c++, team != null ? team.getName() : "", wrap);
            setCellStr(row, c++, t.getCountryMarket(), wrap);
            setCellStr(row, c++, influencerNameById.getOrDefault(t.getInfluencerId(), ""), wrap);
            setCellStr(row, c++, t.getPlatform(),      wrap);
            setCellStr(row, c++, t.getDemandContent(), wrap);
            setCellStr(row, c++, t.getPublishLink(),   wrap);
            setCellStr(row, c++, t.getPublishDate() != null ? df.format(t.getPublishDate()) : "", wrap);
            setCellStr(row, c++, t.getProgress() != null ? t.getProgress().getLabel() : "", wrap);
            setCellStr(row, c++, t.getInfluencerPaymentProgress() != null ? t.getInfluencerPaymentProgress().getLabel() : "", wrap);
            setCellStr(row, c++, t.getVideoType() != null ? t.getVideoType().getLabel() : "", wrap);
            setCellStr(row, c++, t.getOldMaterialSourceLink() != null ? t.getOldMaterialSourceLink() : "", wrap);
            Employee manager = employeeCache.findById(t.getProjectManagerId());
            setCellStr(row, c++, manager != null ? manager.getName() : "", wrap);
            Employee executor = employeeCache.findById(t.getExecutorId());
            setCellStr(row, c++, executor != null ? executor.getName() : "", wrap);
            setCellStr(row, c++, t.getClientOrderId(),     wrap);
            setCellStr(row, c++, t.getClientPaymentBatch(), wrap);
            if (canViewSensitive) {
                setCellMoney(row, c++, t.getInfluencerCost(), wrap);
                setCellMoney(row, c++, t.getClientPrice(),    wrap);
            }
            setCellStr(row, c++, t.getNotes(), wrap);
        }

        for (int i = 0; i < hc; i++) sheet.setColumnWidth(i, 18 * 256);
        writeOut(wb, response, "红人合作跟踪");
    }

    // ============ 模板 ============
    public void downloadTemplate(boolean canViewSensitive, HttpServletResponse response) throws IOException {
        XSSFWorkbook wb = new XSSFWorkbook();
        XSSFSheet sheet = wb.createSheet("红人合作跟踪");
        XSSFCellStyle hdrN = headerStyle(wb, false);
        XSSFCellStyle hdrS = headerStyle(wb, true);
        XSSFCellStyle wrap = wrapStyle(wb);

        Row header = sheet.createRow(0);
        Map<String, Integer> colIdxMap = new HashMap<String, Integer>();
        int hc = 0;
        for (String[] col : COLUMNS) {
            if (isExportOnly(col)) continue;   // 仅导出列，模板跳过
            if ("1".equals(col[1]) && !canViewSensitive) continue;
            Cell cell = header.createCell(hc);
            cell.setCellValue(col[0]);
            cell.setCellStyle("1".equals(col[1]) ? hdrS : hdrN);
            colIdxMap.put(col[0], hc);
            hc++;
        }

        // 视频项目进度 / 红人结款进度 下拉
        DataValidationHelper dv = sheet.getDataValidationHelper();
        addDropdown(sheet, dv, colIdxMap, "视频项目进度", PROGRESS_LABELS);
        addDropdown(sheet, dv, colIdxMap, "红人结款进度", PAYMENT_PROGRESS_LABELS);
        addDropdown(sheet, dv, colIdxMap, "项目视频类型", VIDEO_TYPE_LABELS);

        // "红人结款进度"/"视频发布时间"表头各加一条批注提示前置条件，避免误填
        addHeaderComment(sheet, wb, colIdxMap, "红人结款进度", PAYMENT_PROGRESS_HINT);
        addHeaderComment(sheet, wb, colIdxMap, "视频发布时间", PUBLISH_DATE_HINT);
        // 两个金额列加批注提示必须是数字，避免误填文本备注
        addHeaderComment(sheet, wb, colIdxMap, "红人视频制作与发布成本（美金）", MONEY_FIELD_HINT);
        addHeaderComment(sheet, wb, colIdxMap, "客户合作价格（美金）", MONEY_FIELD_HINT);

        // 示例行
        Map<String, String> ex = new HashMap<String, String>();
        ex.put("品牌方", "TEMU");
        ex.put("红人社媒完整名字", "bigdogtech");
        ex.put("合作平台", "Instagram\nTikTok");
        ex.put("需求内容(具体产品名)", "手持游戏机");
        ex.put("视频发布链接", "https://instagram.com/p/xxx");
        ex.put("视频发布时间", "2026-04-09");
        ex.put("视频项目进度", "已发布（未结算）");
        ex.put("红人结款进度", "");  // 默认留空，只有视频项目进度达到前置条件才启用，具体见表头批注
        ex.put("项目视频类型", "实拍新视频");
        ex.put("采买旧视频的原链接", "");  // 仅"项目视频类型"为"旧素材重发"时才填写，其余情况留空
        ex.put("项目负责人", "梁珈绫 Charlene");
        ex.put("内部执行人员", "梁珈绫 Charlene");
        ex.put("客户方的项目订单", "6004980428");
        ex.put("客户方付款批次", "已加入未结算列表");
        ex.put("红人视频制作与发布成本（美金）", "550");
        ex.put("客户合作价格（美金）", "800");
        ex.put("红人团队", "");  // 该红人在选中的品牌方下只有0/1个团队时可以留空，多个团队时必须填写
        ex.put("备注", "");
        Row exRow = sheet.createRow(1);
        for (String[] col : COLUMNS) {
            Integer idx = colIdxMap.get(col[0]);
            if (idx == null) continue;
            setCellStr(exRow, idx, ex.getOrDefault(col[0], ""), wrap);
        }

        for (int i = 0; i < hc; i++) sheet.setColumnWidth(i, 18 * 256);
        writeOut(wb, response, "红人合作跟踪导入模板");
    }

    // ============ 导入 ============
    /** 保留 MultipartFile 入口，兼容以后可能需要同步调用的场景 */
    public List<String> importData(MultipartFile file, boolean canViewSensitive) throws IOException {
        return importData(file.getInputStream(), canViewSensitive);
    }

    /**
     * 异步导入入口：立即返回，实际处理放到后台线程跑完再回填批次记录。
     * 用有界线程池（importTaskExecutor），避免服务器同时处理太多导入任务。
     */
    @org.springframework.scheduling.annotation.Async("importTaskExecutor")
    public void importDataAsync(Long batchId, byte[] fileBytes, boolean canViewSensitive) {
        ImportBatch batch = importBatchRepo.findById(batchId).orElse(null);
        if (batch == null) return; // 理论上不会发生，防御性判断
        try {
            List<String> errors = importData(new java.io.ByteArrayInputStream(fileBytes), canViewSensitive,
                    (processed, total) -> {
                        // 每处理一批就回写一次进度，前端"导入历史"页面轮询的时候就能看到实时进度
                        batch.setTotalRows(total);
                        batch.setProcessedCount(processed);
                        importBatchRepo.save(batch);
                    });
            String summary = errors.isEmpty() ? "" : errors.get(0);
            batch.setSuccessCount(parseIntFromSummary(summary, "新增 (\\d+) 条"));
            batch.setUpdateCount(parseIntFromSummary(summary, "更新 (\\d+) 条"));
            batch.setFailCount(parseIntFromSummary(summary, "失败 (\\d+) 条"));
            batch.setTotalRows(parseIntFromSummary(summary, "共处理 (\\d+) 行"));
            batch.setProcessedCount(batch.getTotalRows());
            batch.setResultDetail(String.join("\n", errors));
            batch.setStatus("COMPLETED");
        } catch (Exception e) {
            log.error("批次{}导入失败：{}", batchId, e.getMessage(), e);
            batch.setStatus("FAILED");
            batch.setErrorMessage(e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            batch.setCompletedAt(new Date());
            importBatchRepo.save(batch);
        }
    }

    /** 从汇总文字里用正则挖出具体数字，挖不到就给 0（不影响主流程，只是统计数字不准） */
    private int parseIntFromSummary(String summary, String pattern) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(summary);
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 实际的导入逻辑。改成接收 InputStream 而不是 MultipartFile，是因为异步导入场景下
     * （见 CollaborationTrackingController 的异步入口），HTTP 请求已经结束、原始的
     * MultipartFile 早就不能用了，只能先把文件内容读成字节数组存起来，后台线程处理时
     * 用 ByteArrayInputStream 包一层传进来。
     */
    public List<String> importData(InputStream fileStream, boolean canViewSensitive) throws IOException {
        return importData(fileStream, canViewSensitive, (processed, total) -> {});
    }

    /**
     * 带进度回调的版本：每处理完一批（20行）就回调一次，异步导入用这个版本
     * 把进度实时写回"导入批次"记录，前端"导入历史"页面才能看到"处理中"的具体进度。
     * 同步入口传一个空回调就行，不影响原来的行为。
     */
    public List<String> importData(InputStream fileStream, boolean canViewSensitive,
                                    java.util.function.BiConsumer<Integer, Integer> progressCallback) throws IOException {
        List<String> errors = new ArrayList<String>();
        Workbook workbook = WorkbookFactory.create(fileStream);
        // 整个方法体包在 try/finally 里，不管中途从哪个分支 return、还是抛异常，
        // 都保证下面设置的 ThreadLocal 一定会被清理掉
        FORMULA_EVALUATOR.set(workbook.getCreationHelper().createFormulaEvaluator());
        try {
        Sheet sheet = workbook.getSheetAt(0);
        int totalRows = sheet.getLastRowNum();
        if (totalRows < 1) { errors.add("Excel 文件为空"); workbook.close(); return errors; }
        progressCallback.accept(0, totalRows); // 一开始就把总行数汇报出去

        // 列名 -> 列号
        Map<String, Integer> colMap = new HashMap<String, Integer>();
        Row headerRow = sheet.getRow(0);
        for (int c = 0; c < headerRow.getLastCellNum(); c++) {
            Cell cell = headerRow.getCell(c);
            if (cell != null) colMap.put(cell.getStringCellValue().trim(), c);
        }

        // 表头完整性校验：少了关键列（或者列名被改得跟哪个别名都对不上）直接拒绝整个文件，
        // 不再是"这一列找不到就当作没填"这种静默处理——之前"内部执行人员"这一列表头被改名，
        // 结果整列数据全部被当成空值导入，且没有任何报错提示，就是这类问题
        List<String> missingColumns = checkRequiredHeaders(colMap, canViewSensitive);
        if (!missingColumns.isEmpty()) {
            workbook.close();
            errors.add("导入失败：Excel 表头缺少以下必需的列（可能是表头被误改或者删除了），"
                    + "请对照模板核对后重新导入，本次没有导入任何数据：");
            errors.addAll(missingColumns);
            return errors;
        }

        // 预加载红人（按 accountName）和品牌方
        Map<String, Influencer> influencerMap = new HashMap<String, Influencer>();
        for (Influencer inf : influencerRepo.findByIsDeletedFalseOrderByAccountNameAsc()) {
            influencerMap.put(inf.getAccountName(), inf);
        }

        // ============================================================
        // 性能优化：批量预加载这次导入可能用到的所有数据，构造成内存索引（BulkLookupContext），
        // 后面每一行调用 trackingService.saveBulk() 时不再逐行查数据库，
        // 只有真正落库这一步才会有数据库写入。这是解决"导入几百行超时报网络错误"的关键。
        // ============================================================
        List<Long> allInfluencerIds = new ArrayList<Long>();
        for (Influencer inf : influencerMap.values()) allInfluencerIds.add(inf.getId());

        CollaborationTrackingService.BulkLookupContext bulkCtx =
                new CollaborationTrackingService.BulkLookupContext(projectNoGenerator);

        if (!allInfluencerIds.isEmpty()) {
            // 1. 这批红人的"品牌方-团队"关联，按 影响人id -> 品牌方id -> 关联列表 组织好
            for (InfluencerBrandTeam rel : influencerBrandTeamRepo.findByInfluencerIdIn(allInfluencerIds)) {
                bulkCtx.brandTeamMap
                        .computeIfAbsent(rel.getInfluencerId(), k -> new HashMap<Long, List<InfluencerBrandTeam>>())
                        .computeIfAbsent(rel.getBrandId(), k -> new ArrayList<InfluencerBrandTeam>())
                        .add(rel);
            }
            // 2. 这批红人名下已有的跟踪记录，建好查重索引 + 旧素材链接占用索引
            for (CollaborationTracking existing : trackingRepo.findByInfluencerIdInAndIsDeletedFalse(allInfluencerIds)) {
                if (existing.getPublishLink() != null && existing.getPublishDate() != null) {
                    bulkCtx.dedupIndex.put(
                            CollaborationTrackingService.BulkLookupContext.dedupKey(
                                    existing.getInfluencerId(), existing.getPublishLink(), existing.getPublishDate()),
                            existing);
                }
                if (existing.getOldMaterialSourceLinkNormalized() != null) {
                    bulkCtx.normalizedLinkOwner.put(existing.getOldMaterialSourceLinkNormalized(), existing.getId());
                }
            }
        }
        // 3. 全表已用的内部项目编号（只是字符串，量不大，一次性查出来放内存里）
        bulkCtx.usedProjectNos.addAll(trackingRepo.findAllInternalProjectNos());

        // 预加载员工列表，供"项目负责人"列模糊匹配
        List<Employee> allEmployees = employeeCache.getAll();
        // 项目负责人只能选"项目负责人"或"管理层"角色；内部执行人员只能选"执行人员"角色
        List<Employee> projectManagerCandidates = new ArrayList<Employee>();
        List<Employee> executorCandidates = new ArrayList<Employee>();
        for (Employee e : allEmployees) {
            String role = e.getRole();
            if ("项目负责人".equals(role) || "管理层".equals(role)) projectManagerCandidates.add(e);
            if ("执行人员".equals(role)) executorCandidates.add(e);
        }

        int processedCount = 0, createdCount = 0, updatedCount = 0;
        SimpleDateFormat[] dateFormats = {
            new SimpleDateFormat("yyyy-MM-dd"),
            new SimpleDateFormat("yyyy/MM/dd"),
            new SimpleDateFormat("MM/dd/yyyy"),
            new SimpleDateFormat("yyyy.MM.dd")
        };

        for (int i = 1; i <= totalRows; i++) {
            Row row = sheet.getRow(i);
            if (row == null || isRowEmpty(row)) continue;
            processedCount++;
            if (i % 20 == 0 || i == totalRows) progressCallback.accept(i, totalRows);
            try {
                // 红人社媒完整名字（兼容旧列名"红人ID"和"达人名称"）
                String accountName = firstNonNull(
                        getStr(row, colMap, "红人社媒完整名字"),
                        getStr(row, colMap, "红人ID"),
                        getStr(row, colMap, "达人名称"),
                        getStr(row, colMap, "达人"));
                if (accountName == null || accountName.isEmpty()) {
                    errors.add("第" + (i + 1) + "行：红人社媒完整名字为空，跳过"); continue;
                }
                Influencer influencer = influencerMap.get(accountName.trim());
                if (influencer == null) {
                    errors.add("第" + (i + 1) + "行：红人社媒完整名字 [" + accountName + "] 不在红人库，跳过"); continue;
                }

                CollaborationTrackingRequest req = new CollaborationTrackingRequest();
                req.setInfluencerId(influencer.getId());

                // 品牌方：这里只负责按名称查出 id，具体"是否已在红人模块关联"交给 service.save() 统一校验
                String brandName = getStr(row, colMap, "品牌方");
                if (brandName != null && !brandName.isEmpty()) {
                    Brand brand = brandCache.findByName(brandName.trim());
                    if (brand == null) {
                        errors.add("第" + (i + 1) + "行：品牌方 [" + brandName + "] 不存在，请检查品牌方管理模块");
                        continue;
                    }
                    req.setBrandId(brand.getId());
                }

                // 红人团队：这里只负责按名称查出 id（没填就是 null），
                // 具体"这个红人在这个品牌方下能不能用这个团队"交给 service.save() 统一校验
                // （0个选项时必须留空、1个选项时可以留空自动采用、多个选项时必须填对其中一个）
                String teamNameRaw = getStr(row, colMap, "红人团队");
                if (teamNameRaw != null && !teamNameRaw.trim().isEmpty()) {
                    InfluencerTeam team = teamCache.findByName(teamNameRaw.trim());
                    if (team == null) {
                        errors.add("第" + (i + 1) + "行：红人团队 [" + teamNameRaw + "] 不存在，请检查红人团队管理模块");
                        continue;
                    }
                    req.setTeamId(team.getId());
                }

                // 合作平台：优先取"合作平台"列，没有则从"合作资源"/"需求内容"智能提取
                String platformRaw = getStr(row, colMap, "合作平台");
                String demandRaw = firstNonNull(
                        getStr(row, colMap, "需求内容(具体产品名)"),
                        getStr(row, colMap, "需求内容"),
                        getStr(row, colMap, "合作资源"));
                String platform = (platformRaw != null && !platformRaw.isEmpty())
                        ? normalizePlatforms(platformRaw)
                        : extractPlatforms(demandRaw);
                req.setPlatform(platform);
                req.setDemandContent(demandRaw);

                // 发布链接（兼容多种列名）
                String publishLink = emptyToNull(firstNonNull(
                        getStr(row, colMap, "视频发布链接"),
                        getStr(row, colMap, "发布链接(IG reel)"),
                        getStr(row, colMap, "发布链接"),
                        getStr(row, colMap, "主页link")));
                req.setPublishLink(publishLink);

                // 视频发布时间
                Date publishDate = parseDate(row, colMap, dateFormats);
                req.setPublishDate(publishDate);

                // 视频项目进度、红人结款进度、项目视频类型：Excel 导入无论新建还是更新已有记录，都允许带状态
                // 填了但匹配不到有效选项时要报错，不能像以前那样静默地变成空值
                String progressRaw = firstNonNull(
                        getStr(row, colMap, "视频项目进度"),
                        getStr(row, colMap, "进度"));   // 兼容改名前的旧列名
                if (progressRaw != null && !progressRaw.trim().isEmpty()) {
                    CollaborationProgress progress = parseProgress(progressRaw);
                    if (progress == null) {
                        errors.add("第" + (i + 1) + "行：视频项目进度 [" + progressRaw + "] 不是有效选项，请核对");
                        continue;
                    }
                    req.setProgress(progress);
                }

                // 视频发布时间：只有上面解析出来的视频项目进度达到前置条件（已发布(未结算)/
                // 已加入客户未结算列表/客户已结算）时才允许填写，不满足条件却填了值 -> 整行导入失败
                // （不再像以前那样不校验直接接受）
                if (publishDate != null && (req.getProgress() == null || !req.getProgress().allowsPaymentProgress())) {
                    errors.add("第" + (i + 1) + "行：只有\"视频项目进度\"为\"已发布（未结算）\"、\"已加入客户未结算列表\"、"
                            + "\"客户已结算\"时才能填写\"视频发布时间\"，请核对");
                    continue;
                }
                // 红人结款进度：默认空，只有上面解析出来的视频项目进度达到前置条件才允许设置值，
                // 不满足条件时直接报错（不像其他字段那样静默跳过），跟单条保存/状态流转共用同一句错误文案
                String paymentProgressRaw = getStr(row, colMap, "红人结款进度");
                if (paymentProgressRaw != null && !paymentProgressRaw.trim().isEmpty()) {
                    InfluencerPaymentProgress paymentProgress = InfluencerPaymentProgress.fromLabel(paymentProgressRaw);
                    if (paymentProgress == null) {
                        errors.add("第" + (i + 1) + "行：红人结款进度 [" + paymentProgressRaw + "] 不是有效选项，请核对");
                        continue;
                    }
                    if (req.getProgress() == null || !req.getProgress().allowsPaymentProgress()) {
                        errors.add("第" + (i + 1) + "行：" + InfluencerPaymentProgress.PRECONDITION_ERROR);
                        continue;
                    }
                    req.setInfluencerPaymentProgress(paymentProgress);
                }
                String videoTypeRaw = getStr(row, colMap, "项目视频类型");
                if (videoTypeRaw != null && !videoTypeRaw.trim().isEmpty()) {
                    VideoType videoType = VideoType.fromLabel(videoTypeRaw);
                    if (videoType == null) {
                        errors.add("第" + (i + 1) + "行：项目视频类型 [" + videoTypeRaw + "] 不是有效选项，请核对");
                        continue;
                    }
                    req.setVideoType(videoType);
                }
                req.setOldMaterialSourceLink(emptyToNull(getStr(row, colMap, "采买旧视频的原链接")));

                // 客户方的项目订单（兼容"客户系统的订单ID"）
                req.setClientOrderId(emptyToNull(firstNonNull(
                        getStr(row, colMap, "客户方的项目订单"),
                        getStr(row, colMap, "客户系统的订单ID"),
                        getStr(row, colMap, "订单ID"))));

                // 客户方付款批次
                req.setClientPaymentBatch(firstNonNull(
                        getStr(row, colMap, "客户方付款批次"),
                        getStr(row, colMap, "客户付款批次")));

                // 项目负责人：只能是"项目负责人"或"管理层"角色的员工；中文名/英文名模糊匹配，忽略大小写
                String managerRaw = getStr(row, colMap, "项目负责人");
                if (managerRaw != null && !managerRaw.trim().isEmpty()) {
                    Employee manager = matchEmployeeFuzzy(managerRaw, projectManagerCandidates);
                    if (manager == null) {
                        errors.add("第" + (i + 1) + "行：项目负责人 [" + managerRaw + "] 未匹配到任何\"项目负责人\"或\"管理层\"角色的员工，请核对");
                        continue;
                    }
                    req.setProjectManagerId(manager.getId());
                }

                // 内部执行人员：只能是"执行人员"角色的员工；同样的模糊匹配规则
                String executorRaw = getStr(row, colMap, "内部执行人员");
                if (executorRaw != null && !executorRaw.trim().isEmpty()) {
                    Employee executor = matchEmployeeFuzzy(executorRaw, executorCandidates);
                    if (executor == null) {
                        errors.add("第" + (i + 1) + "行：内部执行人员 [" + executorRaw + "] 未匹配到任何\"执行人员\"角色的员工，请核对");
                        continue;
                    }
                    req.setExecutorId(executor.getId());
                }

                // 敏感字段：2026-07 起这两个字段是严格数字，不再允许"价格待定"这类文本备注，
                // 支持公式（只要算出来的结果是数字），单元格留空仍然允许（这两个字段本身选填）
                if (canViewSensitive) {
                    try {
                        req.setInfluencerCost(getMoneyCell(row, colMap,
                                "红人视频制作与发布成本（美金）", "红人成本$", "红人成本"));
                    } catch (NumberFormatException e) {
                        errors.add("第" + (i + 1) + "行：红人视频制作与发布成本（美金）必须是数字");
                        continue;
                    }
                    try {
                        req.setClientPrice(getMoneyCell(row, colMap,
                                "客户合作价格（美金）", "客户合作价格$", "客户合作价格"));
                    } catch (NumberFormatException e) {
                        errors.add("第" + (i + 1) + "行：客户合作价格（美金）必须是数字");
                        continue;
                    }
                }
                req.setNotes(getStr(row, colMap, "备注"));

                // ---- 查重：红人 + 发布链接 + 发布时间 完全相同 -> 更新已有记录，而不是新建 ----
                // 用批量预加载好的内存索引查，不再逐行查数据库
                boolean isUpdate = false;
                CollaborationTracking existingOrNull = null;
                if (publishLink != null && publishDate != null) {
                    existingOrNull = bulkCtx.dedupIndex.get(
                            CollaborationTrackingService.BulkLookupContext.dedupKey(
                                    req.getInfluencerId(), publishLink, publishDate));
                    if (existingOrNull != null) {
                        req.setId(existingOrNull.getId());
                        isUpdate = true;
                    }
                }
                // 注："客户方的项目订单"现在就是一个普通的录入字段（"项目订单"模块已废弃），
                // 改这个字段不再有任何联动限制

                // ---- 视频项目进度"倒退"保护：这条记录红人结款进度已有值，且这行想把视频项目进度
                //      改成不满足前置条件的另一个状态 —— 这种改动必须走"状态流转"功能提交管理员审核，
                //      Excel 批量导入不支持这种改动（没有交互式填写审核原因的环节），直接拒绝这一行 ----
                if (existingOrNull != null && existingOrNull.getInfluencerPaymentProgress() != null
                        && req.getProgress() != null && !req.getProgress().allowsPaymentProgress()
                        && req.getProgress() != existingOrNull.getProgress()) {
                    errors.add("第" + (i + 1) + "行：该记录\"红人结款进度\"已有值，视频项目进度不能通过Excel导入"
                            + "改回不满足前置条件的状态，请使用系统里的\"状态流转\"功能提交管理员审核，跳过此行");
                    continue;
                }

                // 内部项目编号：新建时会自动生成一次（走内存里的编号池，不查库）；
                // 命中更新分支时 id 不为空，会保留数据库里原有的编号，不会重新生成
                trackingService.saveBulk(req, influencer, existingOrNull, bulkCtx);

                if (isUpdate) updatedCount++; else createdCount++;
            } catch (Exception e) {
                log.error("合作跟踪导入第{}行失败：{}", (i + 1), e.getMessage(), e);
                errors.add("第" + (i + 1) + "行导入失败：" + e.getMessage());
            }
        }

        workbook.close();

        errors.add(0, "新增 " + createdCount + " 条，更新 " + updatedCount + " 条，失败 " + (errors.size())
                + " 条（共处理 " + processedCount + " 行）");
        return errors;
        } finally {
            FORMULA_EVALUATOR.remove();
        }
    }

    // ============ 平台智能提取 ============
    /**
     * 从杂乱文本中提取平台，返回换行符分隔的标准平台名
     * 识别规则（大小写不敏感）：
     *   IG / instagram / reel        -> Instagram
     *   TT / tiktok / 抖音国际        -> TikTok
     *   YT / youtube                  -> YouTube
     *   FB / facebook                 -> Facebook
     *   微博 / weibo                  -> 微博
     *   小红书 / xhs / red            -> 小红书
     *   抖音(国内)                    -> 抖音
     */
    private String extractPlatforms(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String u = raw.toUpperCase();
        LinkedHashSet<String> set = new LinkedHashSet<String>();
        if (u.contains("INSTAGRAM") || u.contains("IG") || u.contains("REEL")) set.add("Instagram");
        if (u.contains("TIKTOK") || matchesToken(u, "TT")) set.add("TikTok");
        if (u.contains("YOUTUBE") || matchesToken(u, "YT")) set.add("YouTube");
        if (u.contains("FACEBOOK") || matchesToken(u, "FB")) set.add("Facebook");
        if (raw.contains("微博") || u.contains("WEIBO")) set.add("微博");
        if (raw.contains("小红书") || u.contains("XHS") || u.contains("RED")) set.add("小红书");
        if (raw.contains("抖音")) set.add("抖音");
        return set.isEmpty() ? null : String.join("\n", set);
    }

    /** 标准化已是平台值的内容（如前端导出的"Instagram\nTikTok"或逗号分隔） */
    private String normalizePlatforms(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        // 先尝试当作标准平台值切分；切出来的若识别不出，再走智能提取
        String extracted = extractPlatforms(raw);
        return extracted != null ? extracted : raw.trim();
    }

    /** 判断缩写 token 是否作为独立单词出现（避免 "TT" 误匹配 "ATTACK"） */
    private boolean matchesToken(String upperText, String token) {
        // 用非字母数字边界判断
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(^|[^A-Z0-9])" + token + "([^A-Z0-9]|$)")
                .matcher(upperText);
        return m.find();
    }

    // ============ 工具方法 ============
    private CollaborationProgress parseProgress(String label) {
        if (label == null || label.trim().isEmpty()) return null;
        return CollaborationProgress.fromLabel(label.trim());
    }

    private Date parseDate(Row row, Map<String, Integer> colMap, SimpleDateFormat[] formats) {
        Integer idx = firstNonNullIdx(colMap, "视频发布时间", "发布时间", "发布日期");
        if (idx == null) return null;
        Cell cell = row.getCell(idx);
        if (cell == null) return null;
        // Excel 原生日期
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getDateCellValue();
        }
        // 公式算出来的日期（比如"=TODAY()-5"），求值后如果结果本身是按日期格式显示的，直接取日期值
        if (cell.getCellType() == CellType.FORMULA) {
            FormulaEvaluator evaluator = FORMULA_EVALUATOR.get();
            if (evaluator != null) {
                CellValue value = evaluator.evaluate(cell);
                if (value != null && value.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                    return DateUtil.getJavaDate(value.getNumberValue());
                }
            }
        }
        String s = getStrByIdx(cell);
        if (s == null || s.isEmpty()) return null;
        for (SimpleDateFormat fmt : formats) {
            try { fmt.setLenient(false); return fmt.parse(s); } catch (Exception ignored) {}
        }
        return null;
    }

    private Integer firstNonNullIdx(Map<String, Integer> colMap, String... headers) {
        for (String h : headers) if (colMap.get(h) != null) return colMap.get(h);
        return null;
    }

    private String getStrByIdx(Cell cell) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.FORMULA) {
            // 公式单元格（比如"=400*0.65"）：用求值器算出真正的结果，再按结果类型处理，
            // 不能直接读，直接读只能拿到公式文本本身，而且下面 switch 也没有 FORMULA 这个分支
            FormulaEvaluator evaluator = FORMULA_EVALUATOR.get();
            if (evaluator == null) return null;
            CellValue value = evaluator.evaluate(cell);
            if (value == null) return null;
            switch (value.getCellType()) {
                case STRING:  return value.getStringValue().trim();
                case NUMERIC: return formatNumericCell(value.getNumberValue());
                case BOOLEAN: return String.valueOf(value.getBooleanValue());
                default:      return null; // 公式出错(ERROR)或者算出来是空的
            }
        }
        switch (cell.getCellType()) {
            case STRING:  return cell.getStringCellValue().trim();
            case NUMERIC: return formatNumericCell(cell.getNumericCellValue());
            default:      return null;
        }
    }

    /**
     * 把 Excel 数字单元格的值转成字符串，不丢小数位。
     *
     * 之前这里是 String.valueOf((long) d)，强制转 long 会把小数部分直接砍掉——
     * 这就是"客户合作价格 266.6 导入后变成 266"这个 bug 的根因：只要红人合作跟踪的
     * Excel 里这一列被 Excel 存成"数字"类型（而不是"文本"类型），小数点后的内容
     * 就会在导入这一步被无声地丢弃，写进数据库的从一开始就是被砍过的整数。
     *
     * 整数单元格（比如 400）依然按整数展示成 "400"，不会因为改了这个方法就
     * 无缘无故多出一个 ".0" 尾巴，保持跟以前一样的展示习惯。
     */
    private String formatNumericCell(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return null;
        if (d == Math.rint(d)) {
            return String.valueOf((long) d);
        }
        // 用 BigDecimal.valueOf(double) 而不是 new BigDecimal(double)：
        // 后者会把 266.6 这种数暴露出 double 本身的二进制表示误差，变成
        // 266.60000000000002273737 这类垃圾精度；BigDecimal.valueOf 是基于
        // Double.toString 的最短可回读表示来构造，能得到干净的 "266.6"。
        return java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    /**
     * 按"项目负责人"列文本模糊匹配员工：
     *   - 支持中文名或英文名单独填写（如系统里是"梁珈绫 Charlene"，填"梁珈绫"或"Charlene"均可匹配）
     *   - 若文本完全等于某员工姓名（如直接填"梁珈绫 Charlene"），直接精确匹配
     *   - 若按子串匹配到多个员工（重名歧义），抛出异常要求填写完整姓名
     *   - 匹配不到任何员工，返回 null（不报错，调用方决定如何处理空负责人）
     */
    /**
     * 按姓名模糊匹配员工，中文名/英文名填任一段都能匹配上，忽略大小写。
     * 员工姓名在系统里是"中文名 英文名"存成一个字符串（比如"梁珈绫 Charlene"），
     * 这里按空格拆开分别比较——注意中文输入法打出来的经常是全角空格（　，U+3000），
     * Java 正则默认的 \s 不认这种空格，不特殊处理的话会导致按英文名单独匹配不上，
     * 所以这里统一先把全角空格替换成半角空格再拆分。
     */
    /**
     * 校验 Excel 表头是否包含所有必需的列（每组里只要有一个名字对上就算这一组满足，
     * 兼容历史上用过的各种列名别名）。返回缺失的列组说明，全部满足则返回空列表。
     */
    private List<String> checkRequiredHeaders(Map<String, Integer> colMap, boolean canViewSensitive) {
        // 每一项是"一组可接受的列名"，导入时按 firstNonNull(getStr(...)) 兼容了这些别名，
        // 表头校验也要按同样的分组走，不能只认其中一个名字
        String[][] requiredGroups = {
            {"红人社媒完整名字", "红人ID", "达人名称", "达人"},
            {"品牌方"},
            {"红人团队"},
            {"合作平台"},
            {"需求内容(具体产品名)", "需求内容", "合作资源"},
            {"视频发布链接", "发布链接(IG reel)", "发布链接", "主页link"},
            {"视频发布时间", "发布时间"},   // 兼容改名前的旧列名"发布时间"
            {"视频项目进度", "进度"},   // 兼容改名前的旧列名"进度"
            {"红人结款进度"},
            {"项目视频类型"},
            {"采买旧视频的原链接"},
            {"项目负责人"},
            {"内部执行人员"},
            {"客户方的项目订单", "客户系统的订单ID", "订单ID"},
            {"客户方付款批次", "客户付款批次"},
            {"备注"},
        };

        List<String> missing = new ArrayList<String>();
        for (String[] group : requiredGroups) {
            boolean found = false;
            for (String name : group) {
                if (colMap.containsKey(name)) { found = true; break; }
            }
            if (!found) missing.add("「" + group[0] + "」");
        }

        // 敏感字段（红人成本/客户合作价格）只有当前角色能看到的时候才要求必须存在，
        // 因为没权限的角色下载到的模板本来就不含这两列
        if (canViewSensitive) {
            String[][] sensitiveGroups = {
                {"红人视频制作与发布成本（美金）", "红人成本$", "红人成本"},
                {"客户合作价格（美金）", "客户合作价格$", "客户合作价格"},
            };
            for (String[] group : sensitiveGroups) {
                boolean found = false;
                for (String name : group) {
                    if (colMap.containsKey(name)) { found = true; break; }
                }
                if (!found) missing.add("「" + group[0] + "」");
            }
        }
        return missing;
    }

    /**
     * 按姓名模糊匹配员工，中文名/英文名填任一段都能匹配上，忽略大小写。
     * 员工姓名在系统里是"中文名 英文名"存成一个字符串（比如"梁珈绫 Charlene"），
     * 这里按空格拆开分别比较——注意中文输入法打出来的经常是全角空格（　，U+3000），
     * Java 正则默认的 \s 不认这种空格，不特殊处理的话会导致按英文名单独匹配不上，
     * 所以这里统一先把全角空格替换成半角空格再拆分。
     */
    private Employee matchEmployeeFuzzy(String raw, List<Employee> allEmployees) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String input = normalizeSpaces(raw.trim());

        // 1. 精确匹配（性能最优，且行为最可预期）
        for (Employee e : allEmployees) {
            if (input.equalsIgnoreCase(normalizeSpaces(e.getName().trim()))) return e;
        }

        // 2. 模糊匹配：员工姓名整体包含 input，或 input 是姓名里的某一段（按空格拆分后逐段比较）
        List<Employee> matches = new ArrayList<Employee>();
        for (Employee e : allEmployees) {
            String name = normalizeSpaces(e.getName().trim());
            if (name.equalsIgnoreCase(input)) { matches.add(e); continue; }
            for (String part : name.split("\\s+")) {
                if (part.equalsIgnoreCase(input)) { matches.add(e); break; }
            }
        }

        if (matches.size() == 1) return matches.get(0);
        if (matches.size() > 1) {
            StringBuilder names = new StringBuilder();
            for (Employee e : matches) names.append(e.getName()).append("；");
            throw new RuntimeException("[" + input + "] 匹配到多个员工（"
                    + names + "），请填写完整姓名以消除歧义");
        }
        return null; // 没匹配到
    }

    /** 把全角空格（中文输入法常见）统一替换成半角空格，避免按空格拆分姓名时拆不开 */
    private String normalizeSpaces(String s) {
        return s.replace('\u3000', ' ');
    }

    private String firstNonNull(String... vals) {
        for (String v : vals) if (v != null && !v.trim().isEmpty()) return v.trim();
        return null;
    }

    private String emptyToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    private void writeOut(XSSFWorkbook wb, HttpServletResponse response, String fileName) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String encoded = URLEncoder.encode(fileName, "UTF-8").replaceAll("\\+", "%20");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded + ".xlsx");
        wb.write(response.getOutputStream());
        wb.close();
    }

    private void setCellStr(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
        cell.setCellStyle(style);
    }

    /**
     * 写数字类型的金额单元格（红人视频制作与发布成本 / 客户合作价格专用）。
     * 2026-07 起这两个字段是严格数字，不再有"价格待定"这类文本备注，
     * 所以导出也改成写真正的 Excel 数字单元格（不再是文本+红色高亮），
     * 方便用户在 Excel 里直接拿这两列做后续计算。
     */
    private void setCellMoney(Row row, int col, java.math.BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value != null) cell.setCellValue(value.doubleValue());
        cell.setCellStyle(style);
    }

    private String getStr(Row row, Map<String, Integer> map, String header) {
        Integer idx = map.get(header);
        if (idx == null) return null;
        Cell cell = row.getCell(idx);
        return getStrByIdx(cell);
    }

    /**
     * 读取"必须是数字"的金额列（红人视频制作与发布成本 / 客户合作价格专用，2026-07 起
     * 这两个字段不再允许"价格待定"这类文本备注）。
     *
     * 支持多个候选表头（兼容旧列名），支持公式（只要算出来的结果是数字）。
     * 单元格为空 -> 返回 null（这两个字段本身是选填的，可以不填）。
     * 单元格有内容但读不出数字（比如填了"价格待定"这种文本，或者公式算出来是文本/报错）
     * -> 抛 NumberFormatException，调用方捕获后报"第X行：xxx必须是数字"，不会静默接受成 0 或空。
     */
    private java.math.BigDecimal getMoneyCell(Row row, Map<String, Integer> colMap, String... headers) {
        Integer idx = firstNonNullIdx(colMap, headers);
        if (idx == null) return null;
        Cell cell = row.getCell(idx);
        if (cell == null) return null;

        if (cell.getCellType() == CellType.FORMULA) {
            FormulaEvaluator evaluator = FORMULA_EVALUATOR.get();
            if (evaluator == null) return null;
            CellValue value = evaluator.evaluate(cell);
            if (value == null) return null;
            switch (value.getCellType()) {
                case NUMERIC: return java.math.BigDecimal.valueOf(value.getNumberValue());
                case BLANK:   return null;
                case STRING: {
                    String s = value.getStringValue().trim();
                    if (s.isEmpty()) return null;
                    return new java.math.BigDecimal(s.replaceAll(",", "")); // 解析不出数字会抛 NumberFormatException
                }
                default: throw new NumberFormatException("公式结果不是数字");
            }
        }
        switch (cell.getCellType()) {
            case NUMERIC: return java.math.BigDecimal.valueOf(cell.getNumericCellValue());
            case BLANK:   return null;
            case STRING: {
                String s = cell.getStringCellValue().trim();
                if (s.isEmpty()) return null;
                return new java.math.BigDecimal(s.replaceAll(",", "")); // 解析不出数字会抛 NumberFormatException
            }
            default: throw new NumberFormatException("不是数字");
        }
    }

    private boolean isRowEmpty(Row row) {
        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                String v = cell.getCellType() == CellType.STRING
                        ? cell.getStringCellValue().trim() : "x";
                if (!v.isEmpty()) return false;
            }
        }
        return true;
    }

    private void addDropdown(XSSFSheet sheet, DataValidationHelper dv,
                             Map<String, Integer> colIdxMap, String colName, String[] options) {
        Integer idx = colIdxMap.get(colName);
        if (idx == null) return;
        DataValidation val = dv.createValidation(
                dv.createExplicitListConstraint(options),
                new CellRangeAddressList(1, 1000, idx, idx));
        val.setShowErrorBox(false);
        sheet.addValidationData(val);
    }

    /**
     * 给表头单元格加一条 Excel 原生批注（鼠标悬停在表头上就能看到提示文字），
     * 用于说明一些光看列名看不出来的填写规则（比如"红人结款进度"的前置条件）。
     * 不写进示例行/正文，不会干扰导入时对数据的读取。
     */
    private void addHeaderComment(XSSFSheet sheet, XSSFWorkbook wb,
                                   Map<String, Integer> colIdxMap, String colName, String commentText) {
        Integer idx = colIdxMap.get(colName);
        if (idx == null) return;
        Row header = sheet.getRow(0);
        if (header == null) return;
        Cell cell = header.getCell(idx);
        if (cell == null) return;

        CreationHelper factory = wb.getCreationHelper();
        Drawing<?> drawing = sheet.createDrawingPatriarch();
        ClientAnchor anchor = factory.createClientAnchor();
        anchor.setCol1(idx);
        anchor.setCol2(idx + 4);
        anchor.setRow1(0);
        anchor.setRow2(5);
        Comment comment = drawing.createCellComment(anchor);
        comment.setString(factory.createRichTextString(commentText));
        comment.setAuthor("系统提示");
        cell.setCellComment(comment);
    }

    private XSSFCellStyle headerStyle(XSSFWorkbook wb, boolean sensitive) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        byte[] color = sensitive
                ? new byte[]{(byte)211,(byte)84,(byte)0}
                : new byte[]{(byte)41,(byte)128,(byte)185};
        style.setFillForegroundColor(new XSSFColor(color, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private XSSFCellStyle wrapStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        style.setWrapText(true);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        return style;
    }
}
