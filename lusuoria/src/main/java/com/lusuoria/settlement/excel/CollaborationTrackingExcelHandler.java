package com.lusuoria.settlement.excel;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.dto.request.CollaborationTrackingRequest;
import com.lusuoria.settlement.entity.Brand;
import com.lusuoria.settlement.entity.CollaborationTracking;
import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.enums.CollaborationProgress;
import com.lusuoria.settlement.enums.VideoType;
import com.lusuoria.settlement.repository.CollaborationTrackingRepository;
import com.lusuoria.settlement.repository.InfluencerRepository;
import com.lusuoria.settlement.service.impl.CollaborationTrackingService;
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

    @Autowired private CollaborationTrackingRepository trackingRepo;
    @Autowired private InfluencerRepository influencerRepo;
    @Autowired private BrandCache brandCache;
    @Autowired private EmployeeCache employeeCache;
    @Autowired private CollaborationTrackingService trackingService;

    /** 导出/模板列顺序 */
    // 列定义：[列名, 是否敏感(1=是), 是否仅导出(1=模板不含)]
    private static final String[][] COLUMNS = {
        {"品牌方",                     "0", "0"},
        {"红人团队",                   "0", "1"},  // 仅导出，模板不含（导入时系统自动填充）
        {"服务国家/市场",              "0", "1"},  // 仅导出，模板不含（导入时系统自动填充）
        {"红人社媒完整名字",           "0", "0"},
        {"合作平台",                   "0", "0"},
        {"需求内容(具体产品名)",       "0", "0"},
        {"视频发布链接",               "0", "0"},
        {"发布时间",                   "0", "0"},
        {"进度",                       "0", "0"},
        {"项目视频类型",               "0", "0"},
        {"项目负责人",                 "0", "0"},
        {"客户方的项目订单",           "0", "0"},
        {"客户方付款批次",             "0", "0"},
        {"红人视频制作与发布成本（美金）", "1", "0"},
        {"客户合作价格（美金）",       "1", "0"},
    };

    /** 该列是否仅用于导出（模板跳过） */
    private boolean isExportOnly(String[] col) {
        return col.length > 2 && "1".equals(col[2]);
    }

    private static final String[] PROGRESS_LABELS = {
        "待草稿", "待发布", "待修改", "已发布（未结算）", "暂时延期", "已结算"
    };

    private static final String[] VIDEO_TYPE_LABELS = {
        "实拍新视频", "AI新素材", "旧素材重发"
    };

    // ============ 导出 ============
    public void export(List<CollaborationTracking> list, boolean canViewSensitive,
                       HttpServletResponse response) throws IOException {
        XSSFWorkbook wb = new XSSFWorkbook();
        XSSFSheet sheet = wb.createSheet("红人合作跟踪");
        XSSFCellStyle hdrN = headerStyle(wb, false);
        XSSFCellStyle hdrS = headerStyle(wb, true);
        XSSFCellStyle wrap = wrapStyle(wb);
        XSSFCellStyle red  = redStyle(wb);

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
        int r = 1;
        for (CollaborationTracking t : list) {
            Row row = sheet.createRow(r++);
            int c = 0;
            Brand brand = brandCache.findById(t.getBrandId());
            setCellStr(row, c++, brand != null ? brand.getName() : "", wrap);
            setCellStr(row, c++, t.getTeamName(),      wrap);
            setCellStr(row, c++, t.getCountryMarket(), wrap);
            setCellStr(row, c++, t.getAccountName(),   wrap);
            setCellStr(row, c++, t.getPlatform(),      wrap);
            setCellStr(row, c++, t.getDemandContent(), wrap);
            setCellStr(row, c++, t.getPublishLink(),   wrap);
            setCellStr(row, c++, t.getPublishDate() != null ? df.format(t.getPublishDate()) : "", wrap);
            setCellStr(row, c++, t.getProgress() != null ? t.getProgress().getLabel() : "", wrap);
            setCellStr(row, c++, t.getVideoType() != null ? t.getVideoType().getLabel() : "", wrap);
            Employee manager = employeeCache.findById(t.getProjectManagerId());
            setCellStr(row, c++, manager != null ? manager.getName() : "", wrap);
            setCellStr(row, c++, t.getClientOrderId(),     wrap);
            setCellStr(row, c++, t.getClientPaymentBatch(), wrap);
            if (canViewSensitive) {
                setCellStrColored(row, c++, t.getInfluencerCost(), wrap, red);
                setCellStrColored(row, c++, t.getClientPrice(),    wrap, red);
            }
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

        // 进度下拉
        DataValidationHelper dv = sheet.getDataValidationHelper();
        addDropdown(sheet, dv, colIdxMap, "进度", PROGRESS_LABELS);
        addDropdown(sheet, dv, colIdxMap, "项目视频类型", VIDEO_TYPE_LABELS);

        // 示例行
        Map<String, String> ex = new HashMap<String, String>();
        ex.put("品牌方", "TEMU");
        ex.put("红人社媒完整名字", "bigdogtech");
        ex.put("合作平台", "Instagram\nTikTok");
        ex.put("需求内容(具体产品名)", "手持游戏机");
        ex.put("视频发布链接", "https://instagram.com/p/xxx");
        ex.put("发布时间", "2026-04-09");
        ex.put("进度", "已发布（未结算）");
        ex.put("项目视频类型", "实拍新视频");
        ex.put("项目负责人", "梁珈绫 Charlene");
        ex.put("客户方的项目订单", "6004980428");
        ex.put("客户方付款批次", "已加入未结算列表");
        ex.put("红人视频制作与发布成本（美金）", "550");
        ex.put("客户合作价格（美金）", "800");
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
    public List<String> importData(MultipartFile file, boolean canViewSensitive) throws IOException {
        List<String> errors = new ArrayList<String>();
        Workbook workbook = WorkbookFactory.create(file.getInputStream());
        Sheet sheet = workbook.getSheetAt(0);
        int totalRows = sheet.getLastRowNum();
        if (totalRows < 1) { errors.add("Excel 文件为空"); workbook.close(); return errors; }

        // 列名 -> 列号
        Map<String, Integer> colMap = new HashMap<String, Integer>();
        Row headerRow = sheet.getRow(0);
        for (int c = 0; c < headerRow.getLastCellNum(); c++) {
            Cell cell = headerRow.getCell(c);
            if (cell != null) colMap.put(cell.getStringCellValue().trim(), c);
        }

        // 预加载红人（按 accountName）和品牌方
        Map<String, Influencer> influencerMap = new HashMap<String, Influencer>();
        for (Influencer inf : influencerRepo.findByIsDeletedFalseOrderByAccountNameAsc()) {
            influencerMap.put(inf.getAccountName(), inf);
        }

        // 预加载员工列表，供"项目负责人"列模糊匹配
        List<Employee> allEmployees = employeeCache.getAll();

        int processedCount = 0, createdCount = 0, updatedCount = 0, projectOrderLinked = 0;
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
                req.setAccountName(influencer.getAccountName());

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

                // 发布时间
                Date publishDate = parseDate(row, colMap, dateFormats);
                req.setPublishDate(publishDate);

                // 进度、项目视频类型：Excel 导入无论新建还是更新已有记录，都允许带状态
                req.setProgress(parseProgress(getStr(row, colMap, "进度")));
                req.setVideoType(VideoType.fromLabel(getStr(row, colMap, "项目视频类型")));

                // 客户方的项目订单（兼容"客户系统的订单ID"）
                req.setClientOrderId(emptyToNull(firstNonNull(
                        getStr(row, colMap, "客户方的项目订单"),
                        getStr(row, colMap, "客户系统的订单ID"),
                        getStr(row, colMap, "订单ID"))));

                // 客户方付款批次
                req.setClientPaymentBatch(firstNonNull(
                        getStr(row, colMap, "客户方付款批次"),
                        getStr(row, colMap, "客户付款批次")));

                // 项目负责人：中文名/英文名模糊匹配（如系统里"梁珈绫 Charlene"，填任一段均可）
                String managerRaw = getStr(row, colMap, "项目负责人");
                if (managerRaw != null && !managerRaw.trim().isEmpty()) {
                    Employee manager = matchEmployeeFuzzy(managerRaw, allEmployees);
                    if (manager == null) {
                        errors.add("第" + (i + 1) + "行：项目负责人 [" + managerRaw + "] 未匹配到任何员工，跳过该字段");
                    } else {
                        req.setProjectManagerId(manager.getId());
                    }
                }

                // 敏感字段
                if (canViewSensitive) {
                    req.setInfluencerCost(firstNonNull(
                            getStr(row, colMap, "红人视频制作与发布成本（美金）"),
                            getStr(row, colMap, "红人成本$"),
                            getStr(row, colMap, "红人成本")));
                    req.setClientPrice(firstNonNull(
                            getStr(row, colMap, "客户合作价格（美金）"),
                            getStr(row, colMap, "客户合作价格$"),
                            getStr(row, colMap, "客户合作价格")));
                }

                // ---- 查重：红人 + 发布链接 + 发布时间 完全相同 -> 更新已有记录，而不是新建 ----
                boolean isUpdate = false;
                if (publishLink != null && publishDate != null) {
                    List<CollaborationTracking> dup = trackingRepo.findDuplicates(
                            req.getAccountName(), publishLink, publishDate, null);
                    if (!dup.isEmpty()) {
                        req.setId(dup.get(0).getId());
                        isUpdate = true;
                    }
                }
                // 注：如果这行数据想改一条已经有关联项目订单的记录的"客户方的项目订单"，
                // service.save() 会直接拒绝（LinkedOrderExistsException），走到下面的 catch 里，
                // 记一条"第X行导入失败"，不会静默覆盖或出现异常行为

                // 内部项目编号：新建时 service 内部会自动生成一次；命中更新分支时 id 不为空，
                // service 会保留数据库里原有的编号，不会重新生成——这里不需要也不应该手动处理
                CollaborationTracking savedTracking = trackingService.save(req, true);

                if (isUpdate) updatedCount++; else createdCount++;
                if (savedTracking.getGeneratedProjectOrderId() != null) projectOrderLinked++;
            } catch (Exception e) {
                log.error("合作跟踪导入第{}行失败：{}", (i + 1), e.getMessage(), e);
                errors.add("第" + (i + 1) + "行导入失败：" + e.getMessage());
            }
        }

        workbook.close();

        errors.add(0, "新增 " + createdCount + " 条，更新 " + updatedCount + " 条，失败 " + (errors.size())
                + " 条（共处理 " + processedCount + " 行）；关联项目订单 " + projectOrderLinked + " 条");
        return errors;
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
        Integer idx = firstNonNullIdx(colMap, "发布时间", "发布日期");
        if (idx == null) return null;
        Cell cell = row.getCell(idx);
        if (cell == null) return null;
        // Excel 原生日期
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getDateCellValue();
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
        switch (cell.getCellType()) {
            case STRING:  return cell.getStringCellValue().trim();
            case NUMERIC: return String.valueOf((long) cell.getNumericCellValue());
            default:      return null;
        }
    }

    /**
     * 按"项目负责人"列文本模糊匹配员工：
     *   - 支持中文名或英文名单独填写（如系统里是"梁珈绫 Charlene"，填"梁珈绫"或"Charlene"均可匹配）
     *   - 若文本完全等于某员工姓名（如直接填"梁珈绫 Charlene"），直接精确匹配
     *   - 若按子串匹配到多个员工（重名歧义），抛出异常要求填写完整姓名
     *   - 匹配不到任何员工，返回 null（不报错，调用方决定如何处理空负责人）
     */
    private Employee matchEmployeeFuzzy(String raw, List<Employee> allEmployees) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String input = raw.trim();

        // 1. 精确匹配（性能最优，且行为最可预期）
        for (Employee e : allEmployees) {
            if (input.equalsIgnoreCase(e.getName().trim())) return e;
        }

        // 2. 模糊匹配：员工姓名整体包含 input，或 input 是姓名里的某一段（按空格拆分后逐段比较）
        List<Employee> matches = new ArrayList<Employee>();
        for (Employee e : allEmployees) {
            String name = e.getName().trim();
            if (name.equalsIgnoreCase(input)) { matches.add(e); continue; }
            for (String part : name.split("\\s+")) {
                if (part.equalsIgnoreCase(input)) { matches.add(e); break; }
            }
        }

        if (matches.size() == 1) return matches.get(0);
        if (matches.size() > 1) {
            StringBuilder names = new StringBuilder();
            for (Employee e : matches) names.append(e.getName()).append("；");
            throw new RuntimeException("项目负责人 [" + input + "] 匹配到多个员工（"
                    + names + "），请填写完整姓名以消除歧义");
        }
        return null; // 没匹配到
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

    private void setCellStrColored(Row row, int col, String value,
                                   CellStyle normalStyle, CellStyle redStyle) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
        cell.setCellStyle(isRemark(value) ? redStyle : normalStyle);
    }

    private boolean isRemark(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        try { Double.parseDouble(value.trim().replaceAll(",", "")); return false; }
        catch (NumberFormatException e) { return true; }
    }

    private String getStr(Row row, Map<String, Integer> map, String header) {
        Integer idx = map.get(header);
        if (idx == null) return null;
        Cell cell = row.getCell(idx);
        return getStrByIdx(cell);
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

    private XSSFCellStyle redStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        style.setWrapText(true);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        XSSFFont font = wb.createFont();
        font.setColor(new XSSFColor(new byte[]{(byte)192,(byte)0,(byte)0}, null));
        style.setFont(font);
        return style;
    }
}
