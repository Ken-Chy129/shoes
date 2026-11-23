package cn.ken.shoes.service;

import cn.ken.shoes.mapper.EbaySpecialFreightMapper;
import cn.ken.shoes.model.entity.EbaySpecialFreightDO;
import cn.ken.shoes.model.excel.EbaySpecialFreightExcel;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * eBay 盈利改价的特殊货号运费：独立维护，每轮改价开始时读取。填的是 AG 链接运费，非 AG 链接按 ×1.2。
 * 货号或物品编号 + 尺码唯一，尺码为空表示全尺码。
 */
@Service
public class EbaySpecialFreightService {

    static final int MAX_IMPORT_ROWS = 20000;
    private static final BigDecimal MAX_FREIGHT = new BigDecimal("100000");

    private final EbaySpecialFreightMapper mapper;

    public EbaySpecialFreightService(EbaySpecialFreightMapper mapper) {
        this.mapper = mapper;
    }

    public List<EbaySpecialFreightDO> list() {
        return mapper.selectAll();
    }

    public void save(String target, String size, BigDecimal freight) {
        mapper.upsert(row(target, size, freight, ""));
    }

    public void delete(Long id) {
        mapper.deleteById(id);
    }

    /** 整表校验通过才写入：同一货号+尺码覆盖，表里没有的新增，已有的不删。 */
    public int importRows(List<EbaySpecialFreightExcel> rows) {
        List<EbaySpecialFreightDO> valid = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int line = 1;
        for (EbaySpecialFreightExcel excel : rows == null ? List.<EbaySpecialFreightExcel>of() : rows) {
            line++;
            if (excel == null || (isBlank(excel.getTarget()) && isBlank(excel.getSize())
                    && excel.getFreight() == null)) {
                continue;
            }
            try {
                valid.add(row(excel.getTarget(), excel.getSize(), excel.getFreight(), "第" + line + "行"));
            } catch (IllegalArgumentException e) {
                errors.add(e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("；", errors.subList(0, Math.min(10, errors.size())))
                    + (errors.size() > 10 ? " 等" + errors.size() + "处错误" : ""));
        }
        if (valid.isEmpty()) {
            throw new IllegalArgumentException("Excel里没有运费数据");
        }
        if (valid.size() > MAX_IMPORT_ROWS) {
            throw new IllegalArgumentException("单次最多导入" + MAX_IMPORT_ROWS + "行");
        }
        valid.forEach(mapper::upsert);
        return valid.size();
    }

    /** 改价用：规则键（{@link EbayProfitPriceService#ruleKey}）→ AG 运费。 */
    public Map<String, BigDecimal> load() {
        Map<String, BigDecimal> result = new HashMap<>();
        for (EbaySpecialFreightDO row : mapper.selectAll()) {
            if (row != null && !isBlank(row.getTarget()) && row.getFreight() != null) {
                result.put(EbayProfitPriceService.ruleKey(row.getTarget(), row.getEuSize()), row.getFreight());
            }
        }
        return result;
    }

    private static EbaySpecialFreightDO row(String target, String size, BigDecimal freight, String where) {
        if (isBlank(target)) {
            throw new IllegalArgumentException(where + "货号或物品编号为空");
        }
        if (freight == null || freight.signum() < 0 || freight.compareTo(MAX_FREIGHT) > 0) {
            throw new IllegalArgumentException(where + "运费必须在0到100000元之间");
        }
        String normalized = EbayProfitPriceService.ruleTarget(target);
        String sizeKey = EbayProfitPriceService.sizeKey(size);
        if (normalized.length() > 64 || sizeKey.length() > 16) {
            throw new IllegalArgumentException(where + "货号或尺码过长");
        }
        EbaySpecialFreightDO row = new EbaySpecialFreightDO();
        row.setTarget(normalized);
        row.setEuSize(sizeKey);
        row.setFreight(freight.setScale(2, RoundingMode.HALF_UP));
        return row;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
