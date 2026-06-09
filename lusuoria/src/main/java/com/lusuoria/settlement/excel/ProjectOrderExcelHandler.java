package com.lusuoria.settlement.excel;

import com.lusuoria.settlement.dto.request.ProjectOrderRequest;
import com.lusuoria.settlement.entity.ProjectOrder;
import com.lusuoria.settlement.enums.ClientStatus;
import com.lusuoria.settlement.enums.InternalSettlementStatus;
import com.lusuoria.settlement.enums.ProjectType;
import com.lusuoria.settlement.repository.BrandRepository;
import com.lusuoria.settlement.repository.EmployeeRepository;
import com.lusuoria.settlement.repository.InfluencerRepository;
import com.lusuoria.settlement.repository.ProjectOrderRepository;
import com.lusuoria.settlement.service.ProjectOrderService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Excel 导入/导出处理器
 *
 * canViewSensitive = true  (ADMIN / AUDITOR)：导出/模板包含完整财务列
 * canViewSensitive = false (STAFF / GUEST)  ：导出/模板完全不含财务列，列不存在
 */
@Component
public class ProjectOrderExcelHandler {

    @Autowired private BrandRepository brandRepo;
    @Autowired private InfluencerRepository influencerRepo;
    @Autowired private EmployeeRepository employeeRepo;
    @Autowired private ProjectOrderRepository projectOrderRepo;

    // ===================================================================
    // 列定义（统一管理，导出和模板共用同一套列顺序）
    // sensitive=true 的列只有有权限的角色才会出现在文件里
    // ===================================================================
    private static class ColDef {
        final String title;
        final boolean sensitive;
        ColDef(String title, boolean sensitive) {
            this.title = title;
            this.sensitive = sensitive;
        }
    }

    /** 导出列定义（有序） */
    private static final List<ColDef> EXPORT_COLS = new ArrayList<ColDef>();
    static {
        // 非敏感
        EXPORT_COLS.add(new ColDef("内部项目编号", false));
        EXPORT_COLS.add(new ColDef("甲方订单号",   false));
        EXPORT_COLS.add(new ColDef("项目月份",     false));
        EXPORT_COLS.add(new ColDef("项目类型",     false));
        EXPORT_COLS.add(new ColDef("品牌方",       false));
        EXPORT_COLS.add(new ColDef("红人团队",     false));
        EXPORT_COLS.add(new ColDef("红人ID",     false));
        EXPORT_COLS.add(new ColDef("合作内容",     false));
        EXPORT_COLS.add(new ColDef("合作数量",     false));
        EXPORT_COLS.add(new ColDef("项目负责人",   false));
        // 非敏感（成本/操作信息，所有角色可见）
        EXPORT_COLS.add(new ColDef("客户单价",     false));
        EXPORT_COLS.add(new ColDef("币种",         false));
        EXPORT_COLS.add(new ColDef("汇率",         false));
        EXPORT_COLS.add(new ColDef("红人单价",     false));
        EXPORT_COLS.add(new ColDef("红人成本",     false));
        // 敏感（收入/利润/提成，仅 ADMIN / AUDITOR）
        EXPORT_COLS.add(new ColDef("客户收入",     true));
        EXPORT_COLS.add(new ColDef("人民币收入",   true));
        EXPORT_COLS.add(new ColDef("其他外部成本", true));
        EXPORT_COLS.add(new ColDef("内部执行成本", true));
        EXPORT_COLS.add(new ColDef("项目毛利",     true));
        EXPORT_COLS.add(new ColDef("可分配利润",   true));
        EXPORT_COLS.add(new ColDef("提成比例",     true));
        EXPORT_COLS.add(new ColDef("负责人提成",   true));
        EXPORT_COLS.add(new ColDef("公司剩余利润", true));
        // 非敏感
        EXPORT_COLS.add(new ColDef("甲方状态",     false));
        EXPORT_COLS.add(new ColDef("合同签署",     false));
        EXPORT_COLS.add(new ColDef("预计到账日",   false));
        EXPORT_COLS.add(new ColDef("实际到账日",   false));
        EXPORT_COLS.add(new ColDef("已到账金额",   false));
        EXPORT_COLS.add(new ColDef("内部状态",     false));
        EXPORT_COLS.add(new ColDef("备注",         false));
        EXPORT_COLS.add(new ColDef("创建时间",     false));
    }

    /**
     * 导入模板列定义（有序）
     * 有权限的列才会出现在模板里，导入时按列顺序解析
     */
    private static final List<ColDef> TEMPLATE_COLS = new ArrayList<ColDef>();
    static {
        TEMPLATE_COLS.add(new ColDef("项目月份(必填,如202604)",             false));
        TEMPLATE_COLS.add(new ColDef("项目类型(必填,海外红人/中国红人)",     false));
        TEMPLATE_COLS.add(new ColDef("品牌方名称(必填)",                    false));
        TEMPLATE_COLS.add(new ColDef("红人ID",                           false));
        TEMPLATE_COLS.add(new ColDef("甲方订单号",                         false));
        TEMPLATE_COLS.add(new ColDef("合作内容",                           false));
        TEMPLATE_COLS.add(new ColDef("合作数量",                           false));
        TEMPLATE_COLS.add(new ColDef("项目负责人",                         false));
        // 非敏感（成本/操作信息，所有角色可见）
        TEMPLATE_COLS.add(new ColDef("客户单价",                           false));
        TEMPLATE_COLS.add(new ColDef("币种(USD/RMB)",                     false));
        TEMPLATE_COLS.add(new ColDef("汇率(外币填写,如7.25)",              false));
        TEMPLATE_COLS.add(new ColDef("红人单价",                           false));
        // 敏感（收入/利润/提成，仅 ADMIN / AUDITOR）
        TEMPLATE_COLS.add(new ColDef("客户收入(必填)",                     true));
        TEMPLATE_COLS.add(new ColDef("其他外部成本",                       true));
        TEMPLATE_COLS.add(new ColDef("内部执行成本",                       true));
        TEMPLATE_COLS.add(new ColDef("提成比例(如0.25表示25%)",            true));
        // 非敏感
        TEMPLATE_COLS.add(new ColDef("备注",                              false));
    }

    // ===================================================================
    // 导出
    // ===================================================================
    public void export(List<ProjectOrder> orders, boolean canViewSensitive,
                       HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        String fileName = (canViewSensitive ? "项目结算_完整版_" : "项目结算_")
                + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()) + ".xlsx";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode(fileName, "UTF-8"));

        XSSFWorkbook wb      = new XSSFWorkbook();
        XSSFSheet    sheet   = wb.createSheet("项目订单");
        XSSFCellStyle hdrN   = createHeaderStyle(wb, false); // 普通表头（蓝色）
        XSSFCellStyle hdrS   = createHeaderStyle(wb, true);  // 敏感表头（橙色，有权限才出现）
        XSSFCellStyle money  = createMoneyStyle(wb);
        XSSFCellStyle normal = createNormalStyle(wb);

        // 写表头，跳过无权限的敏感列
        Row headerRow = sheet.createRow(0);
        int colIdx = 0;
        for (ColDef col : EXPORT_COLS) {
            if (col.sensitive && !canViewSensitive) continue;
            Cell cell = headerRow.createCell(colIdx++);
            cell.setCellValue(col.title);
            cell.setCellStyle(col.sensitive ? hdrS : hdrN);
        }
        for (int i = 0; i < colIdx; i++) sheet.setColumnWidth(i, 18 * 256);

        SimpleDateFormat sdf  = new SimpleDateFormat("yyyy-MM-dd");
        SimpleDateFormat sdtf = new SimpleDateFormat("yyyy-MM-dd HH:mm");

        for (int i = 0; i < orders.size(); i++) {
            ProjectOrder o = orders.get(i);
            Row row = sheet.createRow(i + 1);
            int c = 0;

            // 非敏感基础列（前10列始终写入）
            setCellStr(row, c++, o.getInternalProjectNo(), normal);
            setCellStr(row, c++, o.getClientOrderNo(), normal);
            setCellStr(row, c++, o.getProjectMonth(), normal);
            setCellStr(row, c++, o.getProjectType() != null ? o.getProjectType().getLabel() : "", normal);
            setCellStr(row, c++, o.getBrand() != null ? o.getBrand().getName() : "", normal);
            setCellStr(row, c++, o.getInfluencer() != null ? o.getInfluencer().getTeamNames() : "", normal);
            setCellStr(row, c++, o.getInfluencer() != null ? o.getInfluencer().getAccountName() : "", normal);
            setCellStr(row, c++, o.getCooperationContent(), normal);
            setCellNum(row, c++, o.getCooperationQuantity() != null ? (double) o.getCooperationQuantity() : null, normal);
            setCellStr(row, c++, o.getProjectManager() != null ? o.getProjectManager().getName() : "", normal);

            // 非敏感成本列（所有角色）
            setCellMoney(row, c++, o.getClientUnitPrice(), money);
            setCellStr(row, c++, o.getCurrency(), normal);
            setCellMoney(row, c++, o.getExchangeRate(), money);
            setCellMoney(row, c++, o.getInfluencerUnitPrice(), money);
            setCellMoney(row, c++, o.getInfluencerCost(), money);

            // 敏感列（只有有权限才写入，无权限跳过，列根本不存在）
            if (canViewSensitive) {
                setCellMoney(row, c++, o.getClientRevenue(), money);
                setCellMoney(row, c++, o.getRmbRevenue(), money);
                setCellMoney(row, c++, o.getOtherExternalCost(), money);
                setCellMoney(row, c++, o.getInternalExecutionCost(), money);
                setCellMoney(row, c++, o.getGrossProfit(), money);
                setCellMoney(row, c++, o.getDistributableProfit(), money);
                // 提成比例转百分比
                Cell rateCell = row.createCell(c++);
                rateCell.setCellValue(o.getCommissionRate() != null
                        ? o.getCommissionRate().doubleValue() * 100 + "%" : "");
                rateCell.setCellStyle(normal);
                setCellMoney(row, c++, o.getCommissionAmount(), money);
                setCellMoney(row, c++, o.getCompanyNetProfit(), money);
            }

            // 非敏感尾部列（始终写入）
            setCellStr(row, c++, o.getClientStatus() != null ? o.getClientStatus().getLabel() : "", normal);
            setCellStr(row, c++, Boolean.TRUE.equals(o.getContractSigned()) ? "是" : "否", normal);
            setCellStr(row, c++, o.getExpectedReceiptDate() != null ? sdf.format(o.getExpectedReceiptDate()) : "", normal);
            setCellStr(row, c++, o.getActualReceiptDate()   != null ? sdf.format(o.getActualReceiptDate())   : "", normal);
            setCellMoney(row, c++, o.getReceivedAmount(), money);
            setCellStr(row, c++, o.getInternalStatus() != null ? o.getInternalStatus().getLabel() : "", normal);
            setCellStr(row, c++, o.getNotes(), normal);
            setCellStr(row, c++, o.getCreatedAt() != null ? sdtf.format(o.getCreatedAt()) : "", normal);
        }

        sheet.createFreezePane(0, 1);
        wb.write(response.getOutputStream());
        wb.close();
    }

    // ===================================================================
    // 下载导入模板（按角色权限生成，无权限的列根本不在模板里）
    // ===================================================================
    public void downloadTemplate(boolean canViewSensitive, HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        String fileName = canViewSensitive ? "项目订单导入模板_完整版.xlsx" : "项目订单导入模板.xlsx";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode(fileName, "UTF-8"));

        XSSFWorkbook wb    = new XSSFWorkbook();
        XSSFSheet    sheet = wb.createSheet("项目订单导入");
        XSSFCellStyle hdrN = createHeaderStyle(wb, false);
        XSSFCellStyle hdrS = createHeaderStyle(wb, true);

        // 写表头（只写该角色有权限的列）
        Row headerRow = sheet.createRow(0);
        int colIdx = 0;
        for (ColDef col : TEMPLATE_COLS) {
            if (col.sensitive && !canViewSensitive) continue;
            Cell cell = headerRow.createCell(colIdx++);
            cell.setCellValue(col.title);
            cell.setCellStyle(col.sensitive ? hdrS : hdrN);
            sheet.setColumnWidth(colIdx - 1, 24 * 256);
        }

        // 项目类型下拉（始终在第2列，index=1）
        DataValidationHelper dvHelper = sheet.getDataValidationHelper();
        CellRangeAddressList typeRange = new CellRangeAddressList(1, 1000, 1, 1);
        DataValidationConstraint typeConstraint =
                dvHelper.createExplicitListConstraint(new String[]{"海外红人", "中国红人"});
        DataValidation typeValidation = dvHelper.createValidation(typeConstraint, typeRange);
        typeValidation.setShowErrorBox(true);
        sheet.addValidationData(typeValidation);

        // 示例行（动态填写，只填该角色有权限的列）
        Row example = sheet.createRow(1);
        int ec = 0;
        for (ColDef col : TEMPLATE_COLS) {
            if (col.sensitive && !canViewSensitive) continue;
            Cell cell = example.createCell(ec++);
            cell.setCellValue(getExampleValue(col.title, canViewSensitive));
        }

        // 说明行
        Row noteRow = sheet.createRow(3);
        Cell noteCell = noteRow.createCell(0);
        noteCell.setCellValue(canViewSensitive
                ? "⚠ 第2行为示例数据，填写完毕后请删除示例行。橙色列为财务数据列。"
                : "⚠ 第2行为示例数据，填写完毕后请删除示例行。");
        XSSFCellStyle noteStyle = wb.createCellStyle();
        XSSFFont noteFont = wb.createFont();
        noteFont.setColor(IndexedColors.DARK_RED.getIndex());
        noteStyle.setFont(noteFont);
        noteCell.setCellStyle(noteStyle);

        wb.write(response.getOutputStream());
        wb.close();
    }

    // ===================================================================
    // 导入（按列标题匹配，兼容有/无敏感列的两种模板）
    // ===================================================================
    public List<String> importData(MultipartFile file, ProjectOrderService service) throws IOException {
        List<String> errors = new ArrayList<String>();
        Workbook workbook = WorkbookFactory.create(file.getInputStream());
        Sheet sheet = workbook.getSheetAt(0);

        int totalRows = sheet.getLastRowNum();
        if (totalRows < 1) { errors.add("Excel 文件为空"); workbook.close(); return errors; }

        // 读取第一行表头，建立 列名 -> 列索引 的映射（兼容两种模板）
        Row headerRow = sheet.getRow(0);
        Map<String, Integer> colIndexMap = new HashMap<String, Integer>();
        for (int c = 0; c < headerRow.getLastCellNum(); c++) {
            Cell cell = headerRow.getCell(c);
            if (cell != null) {
                String title = cell.getStringCellValue().trim();
                colIndexMap.put(title, c);
            }
        }

        // 准备名称 -> ID 映射
        Map<String, Long> brandMap      = new HashMap<String, Long>();
        Map<String, Long> influencerMap = new HashMap<String, Long>();
        Map<String, Long> employeeMap   = new HashMap<String, Long>();

        brandRepo.findByIsDeletedFalseOrderByNameAsc()
                .forEach(b -> brandMap.put(b.getName().trim(), b.getId()));
        influencerRepo.findByIsDeletedFalseOrderByAccountNameAsc()
                .forEach(inf -> influencerMap.put(inf.getAccountName().trim(), inf.getId()));
        employeeRepo.findByIsDeletedFalseOrderByNameAsc()
                .forEach(e -> employeeMap.put(e.getName().trim(), e.getId()));

        int successCount = 0;
        int skipCount    = 0;
        for (int i = 1; i <= totalRows; i++) {
            Row row = sheet.getRow(i);
            if (row == null || isRowEmpty(row)) continue;
            try {
                // ---- 读取基础字段 ----
                String projectMonth = getByHeader(row, colIndexMap, "项目月份(必填,如202604)");
                String typeStr      = getByHeader(row, colIndexMap, "项目类型(必填,海外红人/中国红人)");
                String brandName    = getByHeader(row, colIndexMap, "品牌方名称(必填)");
                String accountName  = getByHeader(row, colIndexMap, "红人ID");
                String clientOrderNo = getByHeader(row, colIndexMap, "甲方订单号");
                String cooperationContent = getByHeader(row, colIndexMap, "合作内容");

                // 验证必填
                if (projectMonth == null || projectMonth.isEmpty()) {
                    errors.add("第" + (i + 1) + "行：项目月份不能为空"); continue;
                }
                Long brandId = brandMap.get(brandName != null ? brandName.trim() : "");
                if (brandId == null) {
                    errors.add("第" + (i + 1) + "行：品牌方 [" + brandName + "] 不存在"); continue;
                }

                Long influencerId = null;
                if (accountName != null && !accountName.isEmpty()) {
                    influencerId = influencerMap.get(accountName.trim());
                    if (influencerId == null) {
                        errors.add("第" + (i + 1) + "行：红人ID [" + accountName + "] 不存在"); continue;
                    }
                }

                // ---- 增量判断：检查是否已存在 ----
                boolean alreadyExists;
                if (clientOrderNo != null && !clientOrderNo.isEmpty()) {
                    // 有甲方订单号：用「甲方订单号 + 品牌方 + 月份」唯一判断
                    alreadyExists = projectOrderRepo.existsByClientOrderNoAndBrandAndMonth(
                            clientOrderNo, brandId, projectMonth);
                } else {
                    // 无甲方订单号：用「品牌方 + 月份 + 红人账号 + 合作内容」判断
                    alreadyExists = projectOrderRepo.existsByBrandMonthInfluencerContent(
                            brandId, projectMonth, influencerId,
                            cooperationContent != null && !cooperationContent.isEmpty()
                                    ? cooperationContent : null);
                }

                if (alreadyExists) {
                    skipCount++;
                    continue;  // 跳过，不导入也不报错
                }

                // ---- 构建请求并保存 ----
                ProjectOrderRequest req = new ProjectOrderRequest();
                req.setProjectMonth(projectMonth);
                req.setProjectType("海外红人".equals(typeStr)
                        ? ProjectType.OVERSEAS_INFLUENCER : ProjectType.CHINA_INFLUENCER);
                req.setBrandId(brandId);
                req.setInfluencerId(influencerId);
                req.setClientOrderNo(clientOrderNo);
                req.setCooperationContent(cooperationContent);
                req.setCooperationQuantity(getIntByHeader(row, colIndexMap, "合作数量"));

                String managerName = getByHeader(row, colIndexMap, "项目负责人");
                if (managerName != null && !managerName.isEmpty()) {
                    Long mgId = employeeMap.get(managerName.trim());
                    if (mgId == null) {
                        errors.add("第" + (i + 1) + "行：负责人 [" + managerName + "] 不存在"); continue;
                    }
                    req.setProjectManagerId(mgId);
                }

                req.setClientUnitPrice(getBigDecimalByHeader(row, colIndexMap, "客户单价"));
                req.setCurrency(getByHeader(row, colIndexMap, "币种(USD/RMB)"));
                req.setExchangeRate(getBigDecimalByHeader(row, colIndexMap, "汇率(外币填写,如7.25)"));
                req.setInfluencerUnitPrice(getBigDecimalByHeader(row, colIndexMap, "红人单价"));
                req.setClientRevenue(getBigDecimalByHeader(row, colIndexMap, "客户收入(必填)"));
                req.setOtherExternalCost(getBigDecimalByHeader(row, colIndexMap, "其他外部成本"));
                req.setInternalExecutionCost(getBigDecimalByHeader(row, colIndexMap, "内部执行成本"));
                req.setCommissionRate(getBigDecimalByHeader(row, colIndexMap, "提成比例(如0.25表示25%)"));
                req.setNotes(getByHeader(row, colIndexMap, "备注"));
                req.setClientStatus(ClientStatus.PENDING_SUBMIT);
                req.setInternalStatus(InternalSettlementStatus.PENDING_CALC);

                service.save(req);
                successCount++;
            } catch (Exception e) {
                errors.add("第" + (i + 1) + "行导入失败：" + e.getMessage());
            }
        }
        workbook.close();
        // 汇总结果：区分新增、跳过、失败
        int failCount = errors.size();
        errors.add(0, "成功新增 " + successCount + " 条，跳过重复 " + skipCount + " 条，失败 " + failCount + " 条");
        return errors;
    }

    // ===================================================================
    // 示例值（用于模板示例行）
    // ===================================================================
    private String getExampleValue(String colTitle, boolean canViewSensitive) {
        Map<String, String> examples = new HashMap<String, String>();
        examples.put("项目月份(必填,如202604)",          "202604");
        examples.put("项目类型(必填,海外红人/中国红人)", "海外红人");
        examples.put("品牌方名称(必填)",                 "TEMU");
        examples.put("红人ID",                        "bigdogtech");
        examples.put("甲方订单号",                      "ORD-2024-001");
        examples.put("合作内容",                        "视频拍摄");
        examples.put("合作数量",                        "5");
        examples.put("项目负责人",                      "Charlene");
        examples.put("客户单价",                        "200");
        examples.put("客户收入(必填)",                   "1000");
        examples.put("币种(USD/RMB)",                  "USD");
        examples.put("汇率(外币填写,如7.25)",            "7.25");
        examples.put("红人单价",                        "130");
        examples.put("其他外部成本",                    "0");
        examples.put("内部执行成本",                    "0");
        examples.put("提成比例(如0.25表示25%)",          "0.30");
        examples.put("备注",                            "示例数据，填完后请删除此行");
        return examples.containsKey(colTitle) ? examples.get(colTitle) : "";
    }

    // ===================================================================
    // 样式
    // ===================================================================
    private XSSFCellStyle createHeaderStyle(XSSFWorkbook wb, boolean sensitive) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        // 蓝色：普通列；橙色：敏感财务列
        byte[] color = sensitive
                ? new byte[]{(byte)211, (byte)84,  (byte)0}    // 橙色
                : new byte[]{(byte)41,  (byte)128, (byte)185};  // 蓝色
        style.setFillForegroundColor(new XSSFColor(color, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private XSSFCellStyle createMoneyStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        style.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        style.setAlignment(HorizontalAlignment.RIGHT);
        return style;
    }

    private XSSFCellStyle createNormalStyle(XSSFWorkbook wb) {
        return wb.createCellStyle();
    }

    // ===================================================================
    // Cell 读取工具（按列名定位）
    // ===================================================================
    private String getByHeader(Row row, Map<String, Integer> map, String header) {
        Integer idx = map.get(header);
        if (idx == null) return null; // 列不存在（无权限模板），返回 null
        return getCellStringValue(row.getCell(idx));
    }

    private BigDecimal getBigDecimalByHeader(Row row, Map<String, Integer> map, String header) {
        Integer idx = map.get(header);
        if (idx == null) return null;
        return getCellBigDecimal(row.getCell(idx));
    }

    private Integer getIntByHeader(Row row, Map<String, Integer> map, String header) {
        Integer idx = map.get(header);
        if (idx == null) return null;
        return getCellIntValue(row.getCell(idx));
    }

    // ===================================================================
    // Cell 读取基础工具
    // ===================================================================
    private void setCellStr(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
        cell.setCellStyle(style);
    }

    private void setCellMoney(Row row, int col, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value != null) cell.setCellValue(value.doubleValue());
        cell.setCellStyle(style);
    }

    private void setCellNum(Row row, int col, Double value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value != null) cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private String getCellStringValue(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING:  return cell.getStringCellValue().trim();
            case NUMERIC: return String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
            default:      return "";
        }
    }

    private BigDecimal getCellBigDecimal(Cell cell) {
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC)
                return BigDecimal.valueOf(cell.getNumericCellValue());
            if (cell.getCellType() == CellType.STRING) {
                String v = cell.getStringCellValue().trim();
                return v.isEmpty() ? null : new BigDecimal(v);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Integer getCellIntValue(Cell cell) {
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC) return (int) cell.getNumericCellValue();
            if (cell.getCellType() == CellType.STRING) {
                String v = cell.getStringCellValue().trim();
                return v.isEmpty() ? null : Integer.parseInt(v);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private boolean isRowEmpty(Row row) {
        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell != null && cell.getCellType() != CellType.BLANK
                    && !getCellStringValue(cell).isEmpty()) return false;
        }
        return true;
    }
}
