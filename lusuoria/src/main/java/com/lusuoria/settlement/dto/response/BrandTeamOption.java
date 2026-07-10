package com.lusuoria.settlement.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 某品牌方下可选的红人团队选项（不限具体红人）。
 * teamId/teamName 均为 null 表示"不选团队"这个选项本身合法
 * （这个品牌方下有红人没配团队）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BrandTeamOption {
    private Long teamId;
    private String teamName;
}
