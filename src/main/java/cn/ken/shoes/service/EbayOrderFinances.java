package cn.ken.shoes.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 按订单汇总 eBay Finances API 资金流水，得到卖家实际被扣的手续费、推广费与到手金额（美元）。
 *
 * <ul>
 *   <li>SALE：amount 已扣除成交费等平台费用，totalFeeAmount 为这些费用；</li>
 *   <li>NON_SALE_CHARGE（feeType=AD_FEE 等广告费）：推广费，单独扣款；</li>
 *   <li>CREDIT：费用返还（广告费返还冲减推广费，其余冲减手续费）；</li>
 *   <li>REFUND / SHIPPING_LABEL / DISPUTE 等：只影响到手金额。</li>
 * </ul>
 * 到手 = 该订单全部流水按 CREDIT 为正、DEBIT 为负求和。
 */
public final class EbayOrderFinances {

    private static final String USD = "USD";

    /** 单个订单汇总；lineWeights 为 SALE 中各 lineItemId 的计费基数，用于多商品订单分摊。 */
    public record OrderFinance(BigDecimal net, BigDecimal fees, BigDecimal adFees,
                               Map<String, BigDecimal> lineFees, Map<String, BigDecimal> lineWeights) {
    }

    private EbayOrderFinances() {
    }

    public static Map<String, OrderFinance> byOrder(Collection<JSONObject> transactions) {
        Map<String, Acc> accs = new LinkedHashMap<>();
        if (transactions == null) {
            return Map.of();
        }
        for (JSONObject tx : transactions) {
            String orderId = orderId(tx);
            BigDecimal amount = amount(tx.getJSONObject("amount"));
            if (orderId == null || amount == null) {
                continue;
            }
            Acc acc = accs.computeIfAbsent(orderId, key -> new Acc());
            boolean credit = "CREDIT".equalsIgnoreCase(tx.getString("bookingEntry"));
            BigDecimal signed = credit ? amount : amount.negate();
            acc.net = acc.net.add(signed);
            String type = upper(tx.getString("transactionType"));
            if ("SALE".equals(type)) {
                BigDecimal fee = amount(tx.getJSONObject("totalFeeAmount"));
                if (fee != null) {
                    acc.fees = acc.fees.add(credit ? fee : fee.negate());
                }
                collectLines(tx.getJSONArray("orderLineItems"), acc, credit);
            } else if ("NON_SALE_CHARGE".equals(type) || "CREDIT".equals(type)) {
                BigDecimal cost = signed.negate();
                if (isAdFee(tx.getString("feeType"))) {
                    acc.adFees = acc.adFees.add(cost);
                } else {
                    acc.fees = acc.fees.add(cost);
                }
            }
        }
        Map<String, OrderFinance> result = new HashMap<>();
        accs.forEach((orderId, acc) -> result.put(orderId,
                new OrderFinance(acc.net, acc.fees, acc.adFees, acc.lineFees, acc.lineWeights)));
        return result;
    }

    private static void collectLines(JSONArray lines, Acc acc, boolean credit) {
        if (lines == null) {
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            JSONObject line = lines.getJSONObject(i);
            String lineItemId = line.getString("lineItemId");
            if (lineItemId == null) {
                continue;
            }
            BigDecimal basis = amount(line.getJSONObject("feeBasisAmount"));
            if (basis != null) {
                acc.lineWeights.merge(lineItemId, basis.abs(), BigDecimal::add);
            }
            JSONArray fees = line.getJSONArray("marketplaceFees");
            if (fees == null) {
                continue;
            }
            for (int j = 0; j < fees.size(); j++) {
                BigDecimal fee = amount(fees.getJSONObject(j).getJSONObject("amount"));
                if (fee != null) {
                    acc.lineFees.merge(lineItemId, credit ? fee : fee.negate(), BigDecimal::add);
                }
            }
        }
    }

    static boolean isAdFee(String feeType) {
        String type = upper(feeType);
        return type.contains("AD_FEE") || type.contains("PROMOTED");
    }

    private static String orderId(JSONObject tx) {
        String orderId = tx.getString("orderId");
        if (orderId != null && !orderId.isBlank()) {
            return orderId;
        }
        JSONArray references = tx.getJSONArray("references");
        if (references == null) {
            return null;
        }
        for (int i = 0; i < references.size(); i++) {
            JSONObject reference = references.getJSONObject(i);
            if ("ORDER_ID".equalsIgnoreCase(reference.getString("referenceType"))) {
                String id = reference.getString("referenceId");
                if (id != null && !id.isBlank()) {
                    return id;
                }
            }
        }
        return null;
    }

    /**
     * 统一换成美元：卖家结算币种非美元时，eBay 会把订单原币（美元）放在 convertedFromValue；
     * 既不是美元也没有美元原值的金额不计入。
     */
    static BigDecimal amount(JSONObject money) {
        if (money == null) {
            return null;
        }
        String currency = money.getString("currency");
        String value = money.getString("value");
        if (currency != null && !USD.equalsIgnoreCase(currency)) {
            value = USD.equalsIgnoreCase(money.getString("convertedFromCurrency"))
                    ? money.getString("convertedFromValue") : null;
        }
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private static final class Acc {
        private BigDecimal net = BigDecimal.ZERO;
        private BigDecimal fees = BigDecimal.ZERO;
        private BigDecimal adFees = BigDecimal.ZERO;
        private final Map<String, BigDecimal> lineFees = new HashMap<>();
        private final Map<String, BigDecimal> lineWeights = new HashMap<>();
    }
}
