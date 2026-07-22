package com.lusuoria.settlement.excel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.config.DomainCache;
import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.config.InfluencerTeamCache;
import com.lusuoria.settlement.config.InfluencerOptions;
import com.lusuoria.settlement.entity.Brand;
import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.entity.InfluencerBrandTeam;
import com.lusuoria.settlement.entity.InfluencerBrandTeamView;
import com.lusuoria.settlement.enums.InfluencerContactStatus;
import com.lusuoria.settlement.enums.ProjectType;
import com.lusuoria.settlement.repository.InfluencerBrandTeamRepository;
import com.lusuoria.settlement.repository.InfluencerRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class InfluencerExcelHandler {

    private static final Logger log = LoggerFactory.getLogger(InfluencerExcelHandler.class);

    /**
     * 公式求值器：Excel 里像"=xxx"这种公式单元格很常见，不处理的话会被 getStr() 判定成
     * "读不出来"返回 null。用 ThreadLocal 而不是普通实例字段是因为这个类是 Spring 单例 Bean，
     * 多个人同时导入时普通实例字段会互相覆盖，ThreadLocal 保证各自独立，互不干扰。
     */
    private static final ThreadLocal<FormulaEvaluator> FORMULA_EVALUATOR = new ThreadLocal<>();


    @Autowired private InfluencerRepository influencerRepo;
    @Autowired private InfluencerBrandTeamRepository influencerBrandTeamRepo;
    @Autowired private BrandCache    brandCache;
    @Autowired private EmployeeCache employeeCache;
    @Autowired private DomainCache   domainCache;
    @Autowired private InfluencerTeamCache teamCache;

    // ===================================================================
    // 导出
    // ===================================================================
    public void export(List<Influencer> influencers, boolean canViewSensitive,
                       HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        String fileName = "红人_" + new SimpleDateFormat("yyyyMMdd").format(new Date()) + ".xlsx";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode(fileName, "UTF-8"));

        XSSFWorkbook wb    = new XSSFWorkbook();
        XSSFSheet    sheet = wb.createSheet("红人");
        XSSFCellStyle hdrN = headerStyle(wb, false);
        XSSFCellStyle hdrS = headerStyle(wb, true);
        XSSFCellStyle wrap = wrapStyle(wb);
        XSSFCellStyle red  = redStyle(wb);

        List<String[]> cols = new ArrayList<String[]>();
        cols.add(new String[]{"品牌方-团队(每行一对,格式:品牌方/团队,团队可省略)", "0"});
        cols.add(new String[]{"红人类型",            "0"});
        cols.add(new String[]{"红人社媒完整名字",    "0"});
        cols.add(new String[]{"服务国家/市场",       "0"});
        cols.add(new String[]{"平台",                "0"});
        cols.add(new String[]{"主页链接",            "0"});
        cols.add(new String[]{"所属领域",            "0"});
        cols.add(new String[]{"粉丝量",              "0"});
        cols.add(new String[]{"建联情况",            "0"});
        cols.add(new String[]{"跟进人",              "0"});
        cols.add(new String[]{"备注",                "0"});
        cols.add(new String[]{"红人视频制作与发布成本（美金）", "1"});
        cols.add(new String[]{"视频投流成本（美金）",          "1"});
        cols.add(new String[]{"视频版权成本（美金）",          "1"});
        cols.add(new String[]{"合作案例链接",        "0"});
        cols.add(new String[]{"红人邮箱",            "0"});
        cols.add(new String[]{"红人电话",            "0"});
        cols.add(new String[]{"红人WhatsApp",        "0"});
        cols.add(new String[]{"红人Line",            "0"});
        cols.add(new String[]{"红人Telegram",        "0"});
        cols.add(new String[]{"已签署合同链接",      "0"});

        Row headerRow = sheet.createRow(0);
        int colIdx = 0;
        for (String[] col : cols) {
            if ("1".equals(col[1]) && !canViewSensitive) continue;
            Cell cell = headerRow.createCell(colIdx++);
            cell.setCellValue(col[0]);
            cell.setCellStyle("1".equals(col[1]) ? hdrS : hdrN);
        }
        for (int i = 0; i < colIdx; i++) sheet.setColumnWidth(i, 20 * 256);

        for (int i = 0; i < influencers.size(); i++) {
            Influencer inf = influencers.get(i);
            Row row = sheet.createRow(i + 1);
            int c = 0;

            setCellStr(row, c++, formatBrandTeamPairs(inf.getBrandTeamPairs()), wrap); // 品牌方-团队（每行一对）
            setCellStr(row, c++, inf.getInfluencerType() != null ? inf.getInfluencerType().getLabel() : "", wrap); // 红人类型
            setCellStr(row, c++, inf.getAccountName(),   wrap);                 // 红人社媒完整名字
            setCellStr(row, c++, inf.getCountryMarket(), wrap);                 // 服务国家/市场
            setCellStr(row, c++, inf.getPlatform(),      wrap);                 // 平台
            setCellStr(row, c++, inf.getLinks(),         wrap);                 // 主页链接
            setCellStr(row, c++, inf.getDomains(),       wrap);                 // 所属领域
            setCellStr(row, c++, inf.getFollowerCount() != null ? String.valueOf(inf.getFollowerCount()) : "", wrap); // 粉丝量
            setCellStr(row, c++, inf.getContactStatus() != null ? inf.getContactStatus().getLabel() : "", wrap); // 建联情况
            setCellStr(row, c++, inf.getFollowerPerson(), wrap);                // 跟进人
            setCellStr(row, c++, inf.getNotes(), wrap);                         // 备注
            if (canViewSensitive) {
                setCellStrColored(row, c++, inf.getInfluencerCost(), wrap, red);  // 红人视频制作与发布成本
                setCellStrColored(row, c++, inf.getAdSpendCost(),    wrap, red);  // 视频投流成本
                setCellStrColored(row, c++, inf.getCopyrightCost(),  wrap, red);  // 视频版权成本
            }
            setCellStr(row, c++, inf.getCasesLinks(),    wrap);                 // 合作案例链接
            setCellStr(row, c++, inf.getEmail(),         wrap);                 // 红人邮箱
            Map<String, String> contacts = parseContacts(inf.getContacts());
            setCellStr(row, c++, contacts.getOrDefault("phone",    ""), wrap);  // 红人电话
            setCellStr(row, c++, contacts.getOrDefault("whatsapp", ""), wrap);  // 红人WhatsApp
            setCellStr(row, c++, contacts.getOrDefault("line",     ""), wrap);  // 红人Line
            setCellStr(row, c++, contacts.getOrDefault("telegram", ""), wrap);  // 红人Telegram
            setCellStr(row, c++, inf.getContractLink(),  wrap);                 // 已签署合同链接
        }

        sheet.createFreezePane(0, 1);
        wb.write(response.getOutputStream());
        wb.close();
    }

    /** 把红人的"品牌方-团队"对列表格式化成 Excel 单元格里的多行文本："品牌方/团队"，团队为空时只写品牌方 */
    private String formatBrandTeamPairs(List<InfluencerBrandTeamView> pairs) {
        if (pairs == null || pairs.isEmpty()) return "";
        List<String> lines = new ArrayList<String>();
        for (InfluencerBrandTeamView p : pairs) {
            if (p.getBrandName() == null) continue;
            lines.add(p.getTeamName() != null ? (p.getBrandName() + "/" + p.getTeamName()) : p.getBrandName());
        }
        return String.join("\n", lines);
    }

    // ===================================================================
    // 下载导入模板
    // ===================================================================
    public void downloadTemplate(boolean canViewSensitive, HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode("红人导入模板.xlsx", "UTF-8"));

        XSSFWorkbook wb    = new XSSFWorkbook();
        XSSFSheet    sheet = wb.createSheet("红人导入");
        XSSFSheet    hide  = wb.createSheet("_lists");
        wb.setSheetHidden(1, true);

        // 国家列表写入隐藏 sheet
        for (int i = 0; i < InfluencerOptions.COUNTRIES.length; i++) {
            hide.createRow(i).createCell(0).setCellValue(InfluencerOptions.COUNTRIES[i]);
        }

        XSSFCellStyle hdrN = headerStyle(wb, false);
        XSSFCellStyle hdrS = headerStyle(wb, true);

        List<String[]> cols = new ArrayList<String[]>();
        cols.add(new String[]{"品牌方-团队(每行一对,格式:品牌方/团队,团队可省略,多对用换行分隔)", "0"});
        cols.add(new String[]{"红人类型(必填)",           "0"});
        cols.add(new String[]{"红人社媒完整名字(必填)",   "0"});
        cols.add(new String[]{"服务国家/市场(多个用换行分隔)", "0"});
        cols.add(new String[]{"平台(多个用换行分隔)",     "0"});
        cols.add(new String[]{"主页链接(多条用换行分隔)", "0"});
        cols.add(new String[]{"所属领域(多个用换行分隔)", "0"});
        cols.add(new String[]{"粉丝量",                   "0"});
        cols.add(new String[]{"建联情况",                 "0"});
        cols.add(new String[]{"跟进人",                   "0"});
        cols.add(new String[]{"备注",                     "0"});
        cols.add(new String[]{"红人视频制作与发布成本（美金）", "1"});
        cols.add(new String[]{"视频投流成本（美金）",          "1"});
        cols.add(new String[]{"视频版权成本（美金）",          "1"});
        cols.add(new String[]{"合作案例链接(多条用换行分隔)", "0"});
        cols.add(new String[]{"红人邮箱",                 "0"});
        cols.add(new String[]{"红人电话",                 "0"});
        cols.add(new String[]{"红人WhatsApp",             "0"});
        cols.add(new String[]{"红人Line",                 "0"});
        cols.add(new String[]{"红人Telegram",             "0"});
        cols.add(new String[]{"已签署合同链接",           "0"});

        Row headerRow = sheet.createRow(0);
        int colIdx = 0;
        Map<String, Integer> colIdxMap = new LinkedHashMap<String, Integer>();
        for (String[] col : cols) {
            if ("1".equals(col[1]) && !canViewSensitive) continue;
            Cell cell = headerRow.createCell(colIdx);
            cell.setCellValue(col[0]);
            cell.setCellStyle("1".equals(col[1]) ? hdrS : hdrN);
            colIdxMap.put(col[0], colIdx);
            sheet.setColumnWidth(colIdx, 24 * 256);
            colIdx++;
        }

        DataValidationHelper dv = sheet.getDataValidationHelper();
        addDropdown(sheet, dv, colIdxMap, "红人类型(必填)", InfluencerOptions.INFLUENCER_TYPES);
        addDropdown(sheet, dv, colIdxMap, "平台(多个用换行分隔)", InfluencerOptions.PLATFORMS);
        // 建联情况：过滤掉空字符串，空字符串在 Excel 下拉里会报警
        String[] contactStatuses = java.util.Arrays.stream(InfluencerOptions.CONTACT_STATUSES)
                .filter(s -> !s.isEmpty()).toArray(String[]::new);
        addDropdown(sheet, dv, colIdxMap, "建联情况", contactStatuses);
        addFormulaDropdown(sheet, dv, colIdxMap, "服务国家/市场(多个用换行分隔)",
                "_lists!$A$1:$A$" + InfluencerOptions.COUNTRIES.length);
        // 品牌方改为多选（换行/逗号分隔），不再用单选下拉

        // 示例行
        Row ex = sheet.createRow(1);
        Map<String, String> examples = new LinkedHashMap<String, String>();
        examples.put("红人类型(必填)",           "海外红人");
        examples.put("红人社媒完整名字(必填)",    "bigdogtech");
        examples.put("品牌方-团队(每行一对,格式:品牌方/团队,团队可省略,多对用换行分隔)",
                "TEMU中国/游琳团队\nTEMU海外");
        examples.put("服务国家/市场(多个用换行分隔)", "美国");
        examples.put("平台",                     "TikTok");
        examples.put("所属领域(多个用换行分隔)", "科技");
        examples.put("粉丝量",                   "500000");
        examples.put("主页链接(多条用换行分隔)", "https://tiktok.com/xxx");
        examples.put("合作案例链接(多条用换行分隔)", "https://youtube.com/xxx");
        examples.put("已签署合同链接",           "https://drive.google.com/xxx");
        examples.put("红人邮箱",                 "influencer@email.com");
        examples.put("红人电话",                 "+1 234 567 8900");
        examples.put("红人WhatsApp",             "+1 234 567 8900");
        examples.put("红人Line",                 "lineID_xxx");
        examples.put("红人Telegram",             "@telegram_xxx");
        examples.put("建联情况",                 "有合作意愿");
        examples.put("跟进人",                   "Charlene");
        examples.put("红人视频制作与发布成本（美金）", "500");
        examples.put("视频投流成本（美金）",           "200");
        examples.put("视频版权成本（美金）",           "300");
        examples.put("备注",                           "示例数据，填完后请删除");
        for (Map.Entry<String, Integer> entry : colIdxMap.entrySet()) {
            String val = examples.get(entry.getKey());
            if (val != null) ex.createCell(entry.getValue()).setCellValue(val);
        }

        wb.write(response.getOutputStream());
        wb.close();
    }

    // ===================================================================
    // 导入
    // ===================================================================
    @Transactional
    public List<String> importData(MultipartFile file, boolean canViewSensitive) throws IOException {
        List<String> errors = new ArrayList<String>();
        Workbook workbook = WorkbookFactory.create(file.getInputStream());
        FORMULA_EVALUATOR.set(workbook.getCreationHelper().createFormulaEvaluator());
        try {
        Sheet sheet = workbook.getSheetAt(0);

        int totalRows = sheet.getLastRowNum();
        if (totalRows < 1) { errors.add("Excel 文件为空"); workbook.close(); return errors; }

        Row headerRow = sheet.getRow(0);
        Map<String, Integer> colMap = new HashMap<String, Integer>();
        for (int c = 0; c < headerRow.getLastCellNum(); c++) {
            Cell cell = headerRow.getCell(c);
            if (cell != null) colMap.put(cell.getStringCellValue().trim(), c);
        }

        // 表头完整性校验：少了关键列（比如表头被误改）直接拒绝整个文件，不再是
        // "这一列找不到就当作没填"这种静默处理
        String[][] requiredGroups = {
            {"红人社媒完整名字(必填)", "红人社媒完整名字", "红人ID(必填)", "红人ID"},
            {"红人类型(必填)", "红人类型"},
            {"品牌方-团队(每行一对,格式:品牌方/团队,团队可省略,多对用换行分隔)",
             "品牌方-团队(每行一对,格式:品牌方/团队,团队可省略)", "品牌方(多个用换行分隔)"},
            {"服务国家/市场(多个用换行分隔)", "服务国家/市场"},
            {"主页链接(多条用换行分隔)", "主页链接"},
            {"所属领域(多个用换行分隔)", "所属领域"},
            {"合作案例链接(多条用换行分隔)", "合作案例链接"},
            {"已签署合同链接"},
            {"粉丝量"},
            {"建联情况"},
            {"跟进人"},
            {"红人电话"},
            {"红人WhatsApp"},
            {"红人Line"},
            {"红人Telegram"},
            {"红人邮箱"},
            {"备注"},
        };
        List<String> missingColumns = new ArrayList<String>();
        for (String[] group : requiredGroups) {
            boolean found = false;
            for (String name : group) if (colMap.containsKey(name)) { found = true; break; }
            if (!found) missingColumns.add("「" + group[0] + "」");
        }
        if (canViewSensitive) {
            String[] sensitiveHeaders = {"红人视频制作与发布成本（美金）", "视频投流成本（美金）", "视频版权成本（美金）"};
            for (String h : sensitiveHeaders) {
                if (!colMap.containsKey(h)) missingColumns.add("「" + h + "」");
            }
        }
        if (!missingColumns.isEmpty()) {
            workbook.close();
            errors.add("导入失败：Excel 表头缺少以下必需的列（可能是表头被误改或者删除了），"
                    + "请对照模板核对后重新导入，本次没有导入任何数据：");
            errors.addAll(missingColumns);
            return errors;
        }

        // ---- 预检：找出 Excel 里重复的红人ID，提示用户 ----
        Map<String, List<Integer>> accountRowMap = new LinkedHashMap<String, List<Integer>>();
        for (int i = 1; i <= totalRows; i++) {
            Row row = sheet.getRow(i);
            if (row == null || isRowEmpty(row)) continue;
            String accountName = readAccountName(row, colMap);
            if (accountName != null && !accountName.isEmpty()) {
                accountRowMap.computeIfAbsent(accountName.trim(),
                        k -> new java.util.ArrayList<Integer>()).add(i + 1);
            }
        }
        Set<String> duplicateAccounts = new HashSet<String>();
        for (Map.Entry<String, List<Integer>> entry : accountRowMap.entrySet()) {
            if (entry.getValue().size() > 1) {
                duplicateAccounts.add(entry.getKey());
                errors.add("红人社媒完整名字 [" + entry.getKey() + "] 在 Excel 中重复出现，位于第 "
                        + entry.getValue().toString() + " 行，请删除重复行后重新导入，本次已跳过重复行");
            }
        }

        int successCount = 0, updateCount = 0, duplicateSkipCount = 0, processedCount = 0;

        // ---- 导入前预加载，避免循环内反复查库 ----
        // 所有现有红人：accountName -> Influencer
        Map<String, Influencer> existingMap = new HashMap<String, Influencer>();
        influencerRepo.findByIsDeletedFalseOrderByAccountNameAsc()
                .forEach(inf -> existingMap.put(inf.getAccountName().trim(), inf));

        // 收集所有需要新建/更新的 Influencer，最后批量 save
        List<Influencer> toSave = new ArrayList<Influencer>();
        // 收集每个红人本次导入解析出的品牌方名称（accountName -> 品牌名集合），
        // 导入结束后统一解析成 id 并写入中间表（红人此时还没有 id，不能在循环内直接写）
        Map<String, Set<String>> pendingBrandTeamPairs = new HashMap<String, Set<String>>();
        // 收集本次导入中出现的新团队和新领域，导入后统一刷新缓存
        Set<String> newTeams   = new HashSet<String>();
        Set<String> newDomains = new HashSet<String>();

        for (int i = 1; i <= totalRows; i++) {
            Row row = sheet.getRow(i);
            if (row == null || isRowEmpty(row)) continue;
            processedCount++;
            try {
                // 兼容导出列名和模板列名
                String accountName = readAccountName(row, colMap);
                if (accountName == null || accountName.isEmpty()) {
                    errors.add("第" + (i + 1) + "行：红人社媒完整名字不能为空"); continue;
                }

                String typeStr = getStr(row, colMap, "红人类型(必填)");
                if (typeStr == null || typeStr.isEmpty())
                    typeStr = getStr(row, colMap, "红人类型");
                if (typeStr == null || typeStr.isEmpty()) {
                    errors.add("第" + (i + 1) + "行：红人类型不能为空"); continue;
                }

                // 查预加载的 Map，不查库
                Influencer inf = existingMap.get(accountName.trim());

                // 重复行：所有出现过的都跳过，让用户自行决定保留哪行
                if (duplicateAccounts.contains(accountName.trim())) {
                    duplicateSkipCount++;
                    continue;
                }

                boolean isNew = (inf == null);
                if (isNew) { inf = new Influencer(); inf.setIsDeleted(false); }

                inf.setInfluencerType(parseType(typeStr));
                inf.setAccountName(accountName);

                // 以下所有字段：Excel 有值才更新，空白则保留数据库原值

                // 品牌方-团队（每行一对，格式：品牌方/团队，团队可省略，多对换行分隔）
                // 品牌方必须是品牌方管理里已存在的名称；团队如果不存在会自动创建（跟手动新建团队一致）
                // 解析结果先记下来，导入循环结束、所有红人都有 id、新团队也建好了以后，再统一写入关联表
                String brandTeamRaw = getStr(row, colMap, "品牌方-团队(每行一对,格式:品牌方/团队,团队可省略,多对用换行分隔)");
                // 兼容"Excel 导出"生成的表头（没有"，多对用换行分隔"这半句），支持导出后原样再导入
                if (brandTeamRaw == null) brandTeamRaw = getStr(row, colMap, "品牌方-团队(每行一对,格式:品牌方/团队,团队可省略)");
                if (brandTeamRaw == null) brandTeamRaw = getStr(row, colMap, "品牌方(多个用换行分隔)"); // 兼容旧模板
                if (hasValue(brandTeamRaw)) {
                    Set<String> pairKeys = new java.util.LinkedHashSet<String>();
                    List<String> notFound = new ArrayList<String>();
                    for (String line : brandTeamRaw.split("[\n\r]+")) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty()) continue;
                        int slashIdx = trimmed.indexOf('/');
                        String brandName = (slashIdx >= 0 ? trimmed.substring(0, slashIdx) : trimmed).trim();
                        String teamNm = (slashIdx >= 0 ? trimmed.substring(slashIdx + 1) : "").trim();
                        if (brandName.isEmpty()) continue;
                        if (brandCache.findByName(brandName) == null) {
                            notFound.add(brandName);
                            continue;
                        }
                        if (!teamNm.isEmpty() && teamCache.findByName(teamNm) == null) {
                            newTeams.add(teamNm);
                        }
                        pairKeys.add(brandName + "|" + teamNm); // teamNm 为空字符串表示"这个品牌下没配团队"
                    }
                    if (!notFound.isEmpty()) {
                        errors.add("第" + (i + 1) + "行：品牌方 " + notFound
                                + " 不存在，请先在品牌方管理模块创建后再导入，该行其余关联已正常处理");
                    }
                    if (!pairKeys.isEmpty()) {
                        pendingBrandTeamPairs.put(accountName.trim(), pairKeys);
                    }
                }

                String countryMarketRaw = getStr(row, colMap, "服务国家/市场(多个用换行分隔)");
                if (countryMarketRaw == null) countryMarketRaw = getStr(row, colMap, "服务国家/市场");
                setIfPresent(inf::setCountryMarket, countryMarketRaw);

                // 链接：有值才更新（先处理链接，再用链接检测平台）
                String linksRaw = getStr(row, colMap, "主页链接(多条用换行分隔)");
                if (linksRaw == null) linksRaw = getStr(row, colMap, "主页链接");
                if (hasValue(linksRaw)) inf.setLinks(parseLinks(linksRaw));

                // 平台：始终以链接识别结果为准，忽略平台列填写的内容
                if (hasValue(inf.getLinks())) {
                    String detected = detectPlatforms(inf.getLinks());
                    if (detected != null) inf.setPlatform(detected);
                }
                // 所属领域：Excel 有值才更新，空白则保留数据库原值——更新时是整个替换成 Excel
                // 里填的这些值（跟单条编辑表单保存的行为一致），不是往数据库原有领域上追加；
                // 也不再默认给"中国红人"补齐默认领域（之前这条自动追加的规则是错的，已去掉）
                String domainsRaw = getStr(row, colMap, "所属领域(多个用换行分隔)");
                if (domainsRaw == null || domainsRaw.isEmpty())
                    domainsRaw = getStr(row, colMap, "所属领域");

                if (hasValue(domainsRaw)) {
                    Set<String> domainSet = new java.util.LinkedHashSet<String>();
                    for (String d : domainsRaw.split("[,\n\r]+")) {
                        String dn = d.trim();
                        if (!dn.isEmpty()) {
                            if (domainCache.findByName(dn) == null) newDomains.add(dn);
                            domainSet.add(dn);
                        }
                    }
                    inf.setDomains(domainSet.isEmpty() ? null : String.join("\n", domainSet));
                }

                // 粉丝量
                String followerStr = getStr(row, colMap, "粉丝量");
                if (hasValue(followerStr)) {
                    try { inf.setFollowerCount(Long.parseLong(followerStr.replaceAll(",", ""))); }
                    catch (NumberFormatException ignored) {}
                }

                String casesRaw = getStr(row, colMap, "合作案例链接(多条用换行分隔)");
                if (casesRaw == null) casesRaw = getStr(row, colMap, "合作案例链接");
                if (hasValue(casesRaw)) inf.setCasesLinks(parseLinks(casesRaw));

                setIfPresent(inf::setContractLink, getStr(row, colMap, "已签署合同链接"));
                setIfPresent(inf::setEmail,        getStr(row, colMap, "红人邮箱"));

                // 联系方式：有任意一个非空才更新
                String phone    = getStr(row, colMap, "红人电话");
                String whatsapp = getStr(row, colMap, "红人WhatsApp");
                String line     = getStr(row, colMap, "红人Line");
                String telegram = getStr(row, colMap, "红人Telegram");
                if (hasValue(phone) || hasValue(whatsapp) || hasValue(line) || hasValue(telegram)) {
                    inf.setContacts(buildContacts(phone, whatsapp, line, telegram));
                }

                // 建联情况：有值才更新
                String contactStatusStr = getStr(row, colMap, "建联情况");
                if (hasValue(contactStatusStr)) {
                    inf.setContactStatus(parseContactStatus(contactStatusStr));
                }

                // 跟进人
                String followerPersonName = getStr(row, colMap, "跟进人");
                if (hasValue(followerPersonName)) {
                    Employee emp = employeeCache.findByName(followerPersonName);
                    if (emp == null) {
                        errors.add("第" + (i + 1) + "行：跟进人 [" + followerPersonName + "] 不存在"); continue;
                    }
                    inf.setFollowerPerson(emp.getName());
                }

                setIfPresent(inf::setNotes, getStr(row, colMap, "备注"));

                if (canViewSensitive) {
                    String oldCost = getStr(row, colMap, "红人视频制作与发布成本（美金）");
                    if (!hasValue(oldCost)) oldCost = getStr(row, colMap, "红人成本（美金）"); // 兼容旧模板列名
                    setIfPresent(inf::setInfluencerCost, oldCost);
                    setIfPresent(inf::setAdSpendCost,    getStr(row, colMap, "视频投流成本（美金）"));
                    setIfPresent(inf::setCopyrightCost,  getStr(row, colMap, "视频版权成本（美金）"));
                }

                if (isNew) {
                    toSave.add(inf);
                    existingMap.put(accountName.trim(), inf);  // 防止同一 Excel 里重复 accountName 被误判
                    successCount++;
                } else {
                    Influencer original = existingMap.get(accountName.trim());
                    if (isDirty(original, inf)) {
                        toSave.add(inf);
                        updateCount++;
                    }
                    // 无变化：既不加入 toSave，也不计入更新数
                }

            } catch (Exception e) {
                log.error("红人导入第{}行失败，红人={}，原因：{}",
                        (i + 1), readAccountName(sheet.getRow(i), colMap),
                        e.getMessage(), e);
                errors.add("第" + (i + 1) + "行导入失败：" + e.getMessage());
            }
        }
        workbook.close();

        // 批量写库（只写有变化的记录）
        influencerRepo.saveAll(toSave);

        // 统一注册新团队和新领域（必须在处理品牌-团队关联对之前，因为下面要用到团队的 id）
        for (String t : newTeams)   teamCache.getOrCreate(t);
        for (String d : newDomains) domainCache.getOrCreate(d);

        // 品牌方-团队关联：只处理真正变化的部分（逻辑跟红人管理页保存时一致），不再"全删再插"
        // （只处理本次 Excel 里明确给了这一列的红人，没填的红人维持原有关联不变）
        if (!pendingBrandTeamPairs.isEmpty()) {
            Map<String, Influencer> savedMap = new HashMap<String, Influencer>();
            influencerRepo.findByIsDeletedFalseOrderByAccountNameAsc()
                    .forEach(inf -> savedMap.put(inf.getAccountName().trim(), inf));
            for (Map.Entry<String, Set<String>> entry : pendingBrandTeamPairs.entrySet()) {
                Influencer inf = savedMap.get(entry.getKey());
                if (inf == null) continue;

                // key: brandId + "|" + teamId（teamId 为空时用 -1 占位，跟 Controller 保持同一套编码方式）
                Set<String> newKeys = new java.util.LinkedHashSet<String>();
                Map<String, Long[]> keyToIds = new HashMap<String, Long[]>(); // key -> {brandId, teamId}
                for (String pairKey : entry.getValue()) {
                    int barIdx = pairKey.indexOf('|');
                    String brandName = pairKey.substring(0, barIdx);
                    String teamNm = pairKey.substring(barIdx + 1);
                    Brand brand = brandCache.findByName(brandName);
                    if (brand == null) continue; // 已在导入循环中报过错，这里跳过
                    Long teamId = teamNm.isEmpty() ? null : (teamCache.findByName(teamNm) != null
                            ? teamCache.findByName(teamNm).getId() : null);
                    String idKey = brand.getId() + "|" + (teamId != null ? teamId : -1L);
                    newKeys.add(idKey);
                    keyToIds.put(idKey, new Long[]{brand.getId(), teamId});
                }

                List<InfluencerBrandTeam> existingRels = influencerBrandTeamRepo.findByInfluencerId(inf.getId());
                Map<String, InfluencerBrandTeam> existingByKey = new HashMap<String, InfluencerBrandTeam>();
                for (InfluencerBrandTeam rel : existingRels) {
                    existingByKey.put(rel.getBrandId() + "|" + (rel.getTeamId() != null ? rel.getTeamId() : -1L), rel);
                }

                // 移除：现有有效关联里，这次 Excel 没给的
                for (InfluencerBrandTeam rel : existingRels) {
                    String key = rel.getBrandId() + "|" + (rel.getTeamId() != null ? rel.getTeamId() : -1L);
                    if (!Boolean.TRUE.equals(rel.getIsDeleted()) && !newKeys.contains(key)) {
                        rel.setIsDeleted(true);
                        influencerBrandTeamRepo.save(rel);
                    }
                }
                // 新增/复活
                for (String key : newKeys) {
                    InfluencerBrandTeam rel = existingByKey.get(key);
                    if (rel == null) {
                        Long[] ids = keyToIds.get(key);
                        rel = new InfluencerBrandTeam();
                        rel.setInfluencerId(inf.getId());
                        rel.setBrandId(ids[0]);
                        rel.setTeamId(ids[1]);
                        rel.setIsDeleted(false);
                        influencerBrandTeamRepo.save(rel);
                    } else if (Boolean.TRUE.equals(rel.getIsDeleted())) {
                        rel.setIsDeleted(false);
                        influencerBrandTeamRepo.save(rel);
                    }
                }
            }
        }

        int skipCount = processedCount - successCount - updateCount - duplicateSkipCount - errors.size();
        StringBuilder summary = new StringBuilder();
        summary.append("新增 ").append(successCount)
               .append(" 条，更新 ").append(updateCount)
               .append(" 条，无变化跳过 ").append(Math.max(0, skipCount))
               .append(" 条");
        if (duplicateSkipCount > 0)
            summary.append("，重复行跳过 ").append(duplicateSkipCount).append(" 条（详见下方提示）");
        summary.append("，失败 ").append(errors.size()).append(" 条");
        errors.add(0, summary.toString());
        return errors;
        } finally {
            FORMULA_EVALUATOR.remove();
        }
    }

    // ===================================================================
    // 工具方法
    // ===================================================================

    /** 解析联系方式 JSON → Map<type, value> */
    private Map<String, String> parseContacts(String json) {
        Map<String, String> map = new HashMap<String, String>();
        if (json == null || json.trim().isEmpty()) return map;
        try {
            // 简单解析：[{"type":"phone","value":"xxx"}, ...]
            String[] items = json.replaceAll("[\\[\\]\\s]", "")
                    .split("\\},\\{");
            for (String item : items) {
                item = item.replaceAll("[{}]", "");
                String type  = extractJsonValue(item, "type");
                String value = extractJsonValue(item, "value");
                if (type != null && value != null) map.put(type, value);
            }
        } catch (Exception ignored) {}
        return map;
    }

    private String extractJsonValue(String item, String key) {
        String search = "\"" + key + "\":\"";
        int start = item.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = item.indexOf("\"", start);
        return end < 0 ? null : item.substring(start, end);
    }

    /** 组装联系方式 JSON */
    private String buildContacts(String phone, String whatsapp, String line, String telegram) {
        StringBuilder sb = new StringBuilder("[");
        appendContact(sb, "phone",    phone);
        appendContact(sb, "whatsapp", whatsapp);
        appendContact(sb, "line",     line);
        appendContact(sb, "telegram", telegram);
        if (sb.length() == 1) return null; // 全部为空
        // 去掉末尾多余的逗号
        if (sb.charAt(sb.length() - 1) == ',') sb.deleteCharAt(sb.length() - 1);
        sb.append("]");
        return sb.toString();
    }

    private void appendContact(StringBuilder sb, String type, String value) {
        if (value != null && !value.trim().isEmpty()) {
            sb.append("{\"type\":\"").append(type)
              .append("\",\"value\":\"").append(value.trim())
              .append("\"},");
        }
    }

    /**
     * 根据链接域名自动检测平台，返回换行符分隔的平台列表
     * 支持：TikTok / Instagram / YouTube / Facebook / 微博 / 小红书 / 抖音
     */
    private String detectPlatforms(String links) {
        if (links == null || links.trim().isEmpty()) return null;
        java.util.LinkedHashSet<String> detected = new java.util.LinkedHashSet<String>();
        for (String link : links.split("[\n,]+")) {
            String l = link.trim().toLowerCase();
            if (l.contains("tiktok.com"))                           detected.add("TikTok");
            else if (l.contains("instagram.com"))                   detected.add("Instagram");
            else if (l.contains("youtube.com") || l.contains("youtu.be")) detected.add("YouTube");
            else if (l.contains("facebook.com") || l.contains("fb.com")) detected.add("Facebook");
            else if (l.contains("weibo.com"))                       detected.add("微博");
            else if (l.contains("xiaohongshu.com") || l.contains("xhslink.com")) detected.add("小红书");
            else if (l.contains("douyin.com"))                      detected.add("抖音");
        }
        return detected.isEmpty() ? null : String.join("\n", detected);
    }

    private ProjectType parseType(String label) {
        if ("中国红人".equals(label))        return ProjectType.CHINA_INFLUENCER;
        if ("境外红人（在华）".equals(label)) return ProjectType.FOREIGN_IN_CHINA;
        return ProjectType.OVERSEAS_INFLUENCER;
    }

    private InfluencerContactStatus parseContactStatus(String label) {
        if (label == null || label.trim().isEmpty()) return null;
        switch (label.trim()) {
            case "未开发":     return InfluencerContactStatus.UNDEVELOPED;
            case "已回复开发信": return InfluencerContactStatus.REPLIED;
            case "有合作意愿":   return InfluencerContactStatus.INTERESTED;
            case "正在合作":     return InfluencerContactStatus.COOPERATING;
            case "已合作过":     return InfluencerContactStatus.COOPERATED;
            default:             return null;
        }
    }

    /**
     * 比较两个 Influencer 对象是否有字段变化
     * 只比较会被 Excel 导入更新的字段，返回 true 表示有变化需要写库
     */
    private boolean isDirty(Influencer original, Influencer updated) {
        if (original == null) return true;
        return !eq(original.getInfluencerType() != null ? original.getInfluencerType().name() : null,
                   updated.getInfluencerType()   != null ? updated.getInfluencerType().name()   : null)
            || !eq(original.getTeamName(),       updated.getTeamName())
            || !eq(original.getCountryMarket(),  updated.getCountryMarket())
            || !eq(original.getPlatform(),       updated.getPlatform())
            || !eq(original.getDomains(),        updated.getDomains())
            || !eqLong(original.getFollowerCount(), updated.getFollowerCount())
            || !eq(original.getLinks(),          updated.getLinks())
            || !eq(original.getCasesLinks(),     updated.getCasesLinks())
            || !eq(original.getContractLink(),   updated.getContractLink())
            || !eq(original.getEmail(),          updated.getEmail())
            || !eq(original.getContacts(),       updated.getContacts())
            || !eq(original.getContactStatus() != null ? original.getContactStatus().name() : null,
                   updated.getContactStatus()   != null ? updated.getContactStatus().name()   : null)
            || !eq(original.getFollowerPerson(), updated.getFollowerPerson())
            || !eq(original.getNotes(),          updated.getNotes())
            || !eq(original.getInfluencerCost(), updated.getInfluencerCost())
            || !eq(original.getAdSpendCost(),    updated.getAdSpendCost())
            || !eq(original.getCopyrightCost(),  updated.getCopyrightCost());
    }

    private boolean eq(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    private boolean eqLong(Long a, Long b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    /** Excel 单元格有实际内容（非null、非空、非纯空格） */
    private boolean hasValue(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /** 读取红人社媒完整名字，兼容新旧列名 */
    private String readAccountName(Row row, Map<String, Integer> colMap) {
        String[] candidates = {
            "红人社媒完整名字(必填)", "红人社媒完整名字", "红人ID(必填)", "红人ID"
        };
        for (String h : candidates) {
            String v = getStr(row, colMap, h);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    /** 只有有值时才调用 setter，空白不覆盖原值 */
    private void setIfPresent(java.util.function.Consumer<String> setter, String value) {
        if (hasValue(value)) setter.accept(value.trim());
    }

    private String parseLinks(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String part : raw.split("[,\n\r]+")) {
            String p = part.trim();
            if (p.contains("http")) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(p);
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private String parseMulti(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String part : raw.split("[,\n\r]+")) {
            String p = part.trim();
            if (!p.isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(p);
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private boolean isRemark(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        try { Double.parseDouble(value.trim()); return false; }
        catch (NumberFormatException e) { return true; }
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

    private String getStr(Row row, Map<String, Integer> map, String header) {
        Integer idx = map.get(header);
        if (idx == null) return null;
        Cell cell = row.getCell(idx);
        if (cell == null) return null;
        if (cell.getCellType() == CellType.FORMULA) {
            FormulaEvaluator evaluator = FORMULA_EVALUATOR.get();
            if (evaluator == null) return null;
            CellValue value = evaluator.evaluate(cell);
            if (value == null) return null;
            switch (value.getCellType()) {
                case STRING:  return value.getStringValue().trim();
                case NUMERIC: return String.valueOf((long) value.getNumberValue());
                case BOOLEAN: return String.valueOf(value.getBooleanValue());
                default:      return null;
            }
        }
        switch (cell.getCellType()) {
            case STRING:  return cell.getStringCellValue().trim();
            case NUMERIC: return String.valueOf((long) cell.getNumericCellValue());
            default:      return null;
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

    private void addFormulaDropdown(XSSFSheet sheet, DataValidationHelper dv,
                                    Map<String, Integer> colIdxMap, String colName, String formula) {
        Integer idx = colIdxMap.get(colName);
        if (idx == null) return;
        DataValidation val = dv.createValidation(
                dv.createFormulaListConstraint(formula),
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
