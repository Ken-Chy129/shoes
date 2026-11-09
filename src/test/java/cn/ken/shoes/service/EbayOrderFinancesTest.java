package cn.ken.shoes.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EbayOrderFinancesTest {

    @Test
    void sumsSaleFeesAdFeesRefundsAndCreditsPerOrder() {
        List<JSONObject> transactions = List.of(
                JSON.parseObject("""
                        {"transactionType":"SALE","bookingEntry":"CREDIT","orderId":"O1",
                         "amount":{"value":"220.00","currency":"USD"},
                         "totalFeeAmount":{"value":"27.01","currency":"USD"},
                         "orderLineItems":[{"lineItemId":"L1","feeBasisAmount":{"value":"247.01","currency":"USD"},
                           "marketplaceFees":[{"feeType":"FINAL_VALUE_FEE","amount":{"value":"27.01","currency":"USD"}}]}]}
                        """),
                JSON.parseObject("""
                        {"transactionType":"NON_SALE_CHARGE","bookingEntry":"DEBIT","feeType":"AD_FEE",
                         "amount":{"value":"10.00","currency":"USD"},
                         "references":[{"referenceId":"O1","referenceType":"ORDER_ID"}]}
                        """),
                JSON.parseObject("""
                        {"transactionType":"CREDIT","bookingEntry":"CREDIT","feeType":"AD_FEE",
                         "amount":{"value":"2.00","currency":"USD"},"orderId":"O1"}
                        """),
                JSON.parseObject("""
                        {"transactionType":"SALE","bookingEntry":"CREDIT","orderId":"O2",
                         "amount":{"value":"780.00","currency":"HKD","convertedFromValue":"100.00","convertedFromCurrency":"USD"},
                         "totalFeeAmount":{"value":"78.00","currency":"HKD","convertedFromValue":"10.00","convertedFromCurrency":"USD"}}
                        """),
                JSON.parseObject("""
                        {"transactionType":"REFUND","bookingEntry":"DEBIT","orderId":"O2",
                         "amount":{"value":"100.00","currency":"USD"}}
                        """),
                JSON.parseObject("""
                        {"transactionType":"TRANSFER","bookingEntry":"DEBIT","amount":{"value":"5.00","currency":"USD"}}
                        """));

        var finances = EbayOrderFinances.byOrder(transactions);

        assertThat(finances).containsOnlyKeys("O1", "O2");
        var first = finances.get("O1");
        assertThat(first.fees()).isEqualByComparingTo("27.01");
        assertThat(first.adFees()).isEqualByComparingTo("8.00");
        assertThat(first.net()).isEqualByComparingTo("212.00");
        assertThat(first.lineFees().get("L1")).isEqualByComparingTo("27.01");
        var second = finances.get("O2");
        assertThat(second.fees()).isEqualByComparingTo("10.00");
        assertThat(second.net()).isEqualByComparingTo("0.00");
    }
}
