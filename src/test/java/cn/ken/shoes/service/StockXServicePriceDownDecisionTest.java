package cn.ken.shoes.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StockXServicePriceDownDecisionTest {

    @Test
    void pricesDownWhenCandidateMeetsProfitAndExcelFloor() {
        StockXService.ProfitDrivenDecision decision = StockXService.decideProfitDrivenListing(
                120, true, false, 110, 100, true, true, "markup");

        assertThat(decision.action()).isEqualTo(StockXService.ProfitDrivenAction.PRICE_DOWN);
        assertThat(decision.targetPrice()).isEqualTo(109);
    }

    @Test
    void keepsCurrentPriceWhenCandidateWouldCrossExcelFloor() {
        StockXService.ProfitDrivenDecision decision = StockXService.decideProfitDrivenListing(
                120, true, false, 110, 110, true, true, "markup");

        assertThat(decision.action()).isEqualTo(StockXService.ProfitDrivenAction.KEEP);
        assertThat(decision.result()).contains("Excel最低价");
    }

    @Test
    void appliesConfiguredUnprofitableAction() {
        StockXService.ProfitDrivenDecision decision = StockXService.decideProfitDrivenListing(
                100, true, true, 100, 0, false, false, "delist");

        assertThat(decision.action()).isEqualTo(StockXService.ProfitDrivenAction.DELETE);
    }

    @Test
    void excelFloorRemainsAHardSafetyGuard() {
        StockXService.ProfitDrivenDecision decision = StockXService.decideProfitDrivenListing(
                90, true, true, 90, 100, false, false, "delist");

        assertThat(decision.action()).isEqualTo(StockXService.ProfitDrivenAction.MARK_UP);
        assertThat(decision.targetPrice()).isEqualTo(190);
    }

    @Test
    void outsideExcelDelistsWhenProfitableButPriceDownUnprofitable() {
        StockXService.ProfitDrivenDecision decision = StockXService.decideProfitDrivenListing(
                120, true, false, 110, 0, true, false, "delist", true);

        assertThat(decision.action()).isEqualTo(StockXService.ProfitDrivenAction.DELETE);
        assertThat(decision.result()).isEqualTo("待下架-压价后不盈利");
    }

    @Test
    void insideExcelKeepsWhenProfitableButPriceDownUnprofitable() {
        StockXService.ProfitDrivenDecision decision = StockXService.decideProfitDrivenListing(
                120, true, false, 110, 0, true, false, "delist", false);

        assertThat(decision.action()).isEqualTo(StockXService.ProfitDrivenAction.KEEP);
        assertThat(decision.result()).isEqualTo("保持-压价后不盈利但当前价盈利");
    }

    @Test
    void outsideExcelKeepsWhenMarkupActionSelected() {
        StockXService.ProfitDrivenDecision decision = StockXService.decideProfitDrivenListing(
                120, true, false, 110, 0, true, false, "markup", true);

        assertThat(decision.action()).isEqualTo(StockXService.ProfitDrivenAction.KEEP);
        assertThat(decision.result()).isEqualTo("保持-压价后不盈利但当前价盈利");
    }
}
