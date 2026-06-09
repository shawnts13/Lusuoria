package com.lusuoria.settlement.excel;

import com.lusuoria.settlement.entity.Brand;
import com.lusuoria.settlement.repository.BrandRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 品牌方 Excel 导入/导出
 * 品牌方无敏感字段，所有角色可见完整数据
 */
@Component
public class BrandExcelHandler {

    @Autowired private BrandRepository brandRepo;

    // ===== 导出 =====
    public void export(List<Brand> brands, HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        String fileName = "品牌方_" + new SimpleDateFormat("yyyyMMdd").format(new Date()) + ".xlsx";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode(fileName, "UTF-8"));

        XSSFWorkbook wb    = new XSSFWorkbook();
        XSSFSheet    sheet = wb.createSheet("品牌方");
        XSSFCellStyle hdr  = createHeaderStyle(wb);
        XSSFCellStyle nor  = createNormalStyle(wb);

        String[] headers = { "品牌方名称", "国家/市场", "合作类型", "联系人", "结算币种", "付款周期", "备注" };
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(hdr);
            sheet.setColumnWidth(i, 20 * 256);
        }

        for (int i = 0; i < brands.size(); i++) {
            Brand b  = brands.get(i);
            Row row  = sheet.createRow(i + 1);
            int c    = 0;
            setCellStr(row, c++, b.getName(),              nor);
            setCellStr(row, c++, b.getCountryMarket(),     nor);
            setCellStr(row, c++, b.getCooperationType(),   nor);
            setCellStr(row, c++, b.getContactPerson(),     nor);
            setCellStr(row, c++, b.getSettlementCurrency(), nor);
            setCellStr(row, c++, b.getPaymentCycle(),      nor);
            setCellStr(row, c++, b.getNotes(),             nor);
        }

        sheet.createFreezePane(0, 1);
        wb.write(response.getOutputStream());
        wb.close();
    }

    // ===== 下载导入模板 =====
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition",
                "attachment;filename=" + java.net.URLEncoder.encode("品牌方导入模板.xlsx", "UTF-8"));

        XSSFWorkbook wb    = new XSSFWorkbook();
        XSSFSheet    sheet = wb.createSheet("品牌方导入");
        XSSFCellStyle hdr  = createHeaderStyle(wb);

        String[] headers = {
            "品牌方名称(必填)", "国家/市场", "合作类型", "联系人",
            "结算币种(USD/RMB)", "付款周期(如月结30天)", "备注"
        };
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(hdr);
            sheet.setColumnWidth(i, 22 * 256);
        }

        // 结算币种下拉
        DataValidationHelper dvHelper = sheet.getDataValidationHelper();
        DataValidation dv = dvHelper.createValidation(
                dvHelper.createExplicitListConstraint(new String[]{"USD", "RMB", "EUR"}),
                new CellRangeAddressList(1, 1000, 4, 4));
        dv.setShowErrorBox(true);
        sheet.addValidationData(dv);

        // 示例行
        Row ex = sheet.createRow(1);
        ex.createCell(0).setCellValue("TEMU");
        ex.createCell(1).setCellValue("美国");
        ex.createCell(2).setCellValue("达人营销");
        ex.createCell(3).setCellValue("张三");
        ex.createCell(4).setCellValue("USD");
        ex.createCell(5).setCellValue("月结30天");
        ex.createCell(6).setCellValue("示例数据，填完后请删除");

        wb.write(response.getOutputStream());
        wb.close();
    }

    // ===== 导入（增量，按品牌方名称去重）=====
    public List<String> importData(MultipartFile file) throws IOException {
        List<String> errors = new ArrayList<String>();
        Workbook workbook = WorkbookFactory.create(file.getInputStream());
        Sheet sheet = workbook.getSheetAt(0);

        int totalRows = sheet.getLastRowNum();
        if (totalRows < 1) { errors.add("Excel 文件为空"); workbook.close(); return errors; }

        // 读取表头列映射
        Row headerRow = sheet.getRow(0);
        Map<String, Integer> colMap = new HashMap<String, Integer>();
        for (int c = 0; c < headerRow.getLastCellNum(); c++) {
            Cell cell = headerRow.getCell(c);
            if (cell != null) colMap.put(cell.getStringCellValue().trim(), c);
        }

        int successCount = 0, skipCount = 0;
        for (int i = 1; i <= totalRows; i++) {
            Row row = sheet.getRow(i);
            if (row == null || isRowEmpty(row)) continue;
            try {
                String name = getStr(row, colMap, "品牌方名称(必填)");
                if (name == null || name.isEmpty()) {
                    errors.add("第" + (i + 1) + "行：品牌方名称不能为空"); continue;
                }
                // 去重：名称已存在则跳过
                if (brandRepo.existsByNameAndIsDeletedFalse(name)) {
                    skipCount++; continue;
                }
                Brand brand = new Brand();
                brand.setIsDeleted(false);
                brand.setName(name);
                brand.setCountryMarket(getStr(row, colMap, "国家/市场"));
                brand.setCooperationType(getStr(row, colMap, "合作类型"));
                brand.setContactPerson(getStr(row, colMap, "联系人"));
                brand.setSettlementCurrency(getStr(row, colMap, "结算币种(USD/RMB)"));
                brand.setPaymentCycle(getStr(row, colMap, "付款周期(如月结30天)"));
                brand.setNotes(getStr(row, colMap, "备注"));
                brandRepo.save(brand);
                successCount++;
            } catch (Exception e) {
                errors.add("第" + (i + 1) + "行导入失败：" + e.getMessage());
            }
        }
        workbook.close();
        int failCount = errors.size();
        errors.add(0, "成功新增 " + successCount + " 条，跳过重复 " + skipCount + " 条，失败 " + failCount + " 条");
        return errors;
    }

    // ===== 工具 =====
    private String getStr(Row row, Map<String, Integer> map, String header) {
        Integer idx = map.get(header);
        if (idx == null) return null;
        Cell cell = row.getCell(idx);
        if (cell == null) return null;
        switch (cell.getCellType()) {
            case STRING:  return cell.getStringCellValue().trim();
            case NUMERIC: return String.valueOf((long) cell.getNumericCellValue());
            default:      return null;
        }
    }

    private void setCellStr(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
        cell.setCellStyle(style);
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

    private XSSFCellStyle createHeaderStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(new XSSFColor(new byte[]{(byte)41,(byte)128,(byte)185}, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private XSSFCellStyle createNormalStyle(XSSFWorkbook wb) {
        return wb.createCellStyle();
    }
}
