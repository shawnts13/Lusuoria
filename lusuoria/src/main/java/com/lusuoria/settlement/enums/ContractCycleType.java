package com.lusuoria.settlement.enums;

/**
 * 品牌方合同签订周期（2026-07 起，跟 requiresInvoice 一起把"按品牌方特殊处理"的业务规则从
 * 硬编码判断改成配置字段）。目前还没有配套的合同上传/管理功能，先落地这个配置项，供以后
 * 做合同管理时直接读取。
 *
 *   ANNUAL         - 一年签一次合同
 *   PER_REQUIREMENT - 一次需求签一次合同
 */
public enum ContractCycleType {
    ANNUAL("一年签一次合同"),
    PER_REQUIREMENT("一次需求签一次合同");

    private final String label;

    ContractCycleType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 根据中文标签反查枚举（Excel 导入用） */
    public static ContractCycleType fromLabel(String label) {
        if (label == null) return null;
        String trimmed = label.trim();
        for (ContractCycleType t : values()) {
            if (t.label.equals(trimmed)) return t;
        }
        return null;
    }
}
