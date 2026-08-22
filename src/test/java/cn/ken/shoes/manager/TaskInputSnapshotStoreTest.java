package cn.ken.shoes.manager;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.common.PriceDownType;
import cn.ken.shoes.model.excel.StockXDelistInputExcel;
import cn.ken.shoes.model.excel.ModelNoSearchExcel;
import cn.ken.shoes.model.excel.ModelSearchListingByModelExcel;
import cn.ken.shoes.model.excel.ModelSearchListingExcel;
import cn.ken.shoes.model.excel.StockXBidInputExcel;
import cn.ken.shoes.model.excel.StockXBidUpdateInputExcel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TaskInputSnapshotStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripsPriceDownAndDelistInputsByTaskId() {
        TaskInputSnapshotStore store = new TaskInputSnapshotStore(tempDir);
        StockXDelistInputExcel delist = new StockXDelistInputExcel();
        delist.setListingId("listing-1");
        delist.setStyleId("SKU-1");

        store.savePriceDown(10L, Map.of("SKU-1:42",
                new ShoesContext.PriceDownConfig(125, false, PriceDownType.POISON_35)));
        store.saveDelist(10L, List.of(delist));

        assertThat(store.loadPriceDown(10L)).hasValueSatisfying(input ->
                assertThat(input.get("SKU-1:42")).isEqualTo(
                        new ShoesContext.PriceDownConfig(125, false, PriceDownType.POISON_35)));
        assertThat(store.loadDelist(10L)).hasValueSatisfying(input -> {
            assertThat(input).singleElement().satisfies(item -> {
                assertThat(item.getListingId()).isEqualTo("listing-1");
                assertThat(item.getStyleId()).isEqualTo("SKU-1");
            });
        });
    }

    @Test
    void roundTripsAllModelSearchOperationInputs() {
        TaskInputSnapshotStore store = new TaskInputSnapshotStore(tempDir);
        ModelNoSearchExcel priceRow = new ModelNoSearchExcel();
        priceRow.setModelNo("STYLE-1");
        priceRow.setSize("US 9");
        ModelSearchListingExcel listingRow = new ModelSearchListingExcel();
        listingRow.setVariantId("variant-1");
        listingRow.setTargetPrice(new BigDecimal("301"));
        listingRow.setQuantity(3);
        ModelSearchListingByModelExcel listingByModelRow = new ModelSearchListingByModelExcel();
        listingByModelRow.setModelNo("STYLE-2");
        listingByModelRow.setSize("EU 42.5");
        listingByModelRow.setTargetPrice(new BigDecimal("302"));
        listingByModelRow.setQuantity(4);

        store.saveModelSearchPriceInput(12L, List.of(priceRow));
        store.saveModelSearchListingInput(13L, List.of(listingRow));
        store.saveModelSearchListingByModelInput(14L, List.of(listingByModelRow));

        assertThat(store.loadModelSearchPriceInput(12L)).hasValueSatisfying(rows ->
                assertThat(rows).singleElement().satisfies(row -> {
                    assertThat(row.getModelNo()).isEqualTo("STYLE-1");
                    assertThat(row.getSize()).isEqualTo("US 9");
                }));
        assertThat(store.loadModelSearchListingInput(13L)).hasValueSatisfying(rows ->
                assertThat(rows).singleElement().satisfies(row -> {
                    assertThat(row.getVariantId()).isEqualTo("variant-1");
                    assertThat(row.getTargetPrice()).isEqualByComparingTo("301");
                    assertThat(row.getQuantity()).isEqualTo(3);
                }));
        assertThat(store.loadModelSearchListingByModelInput(14L)).hasValueSatisfying(rows ->
                assertThat(rows).singleElement().satisfies(row -> {
                    assertThat(row.getModelNo()).isEqualTo("STYLE-2");
                    assertThat(row.getSize()).isEqualTo("EU 42.5");
                    assertThat(row.getTargetPrice()).isEqualByComparingTo("302");
                    assertThat(row.getQuantity()).isEqualTo(4);
                }));
    }

    @Test
    void missingTaskInputReturnsEmptyOptional() {
        TaskInputSnapshotStore store = new TaskInputSnapshotStore(tempDir);

        assertThat(store.loadPriceDown(99L)).isEmpty();
        assertThat(store.loadDelist(99L)).isEmpty();
        assertThat(store.loadCreateBidsInput(99L)).isEmpty();
    }

    @Test
    void roundTripsCreateBidsInput() {
        TaskInputSnapshotStore store = new TaskInputSnapshotStore(tempDir);
        StockXBidInputExcel row = new StockXBidInputExcel();
        row.setStyleId("100289469");
        row.setSize("US M 4.5");
        row.setPrice(new BigDecimal("1"));

        store.saveCreateBidsInput(15L, List.of(row));

        assertThat(store.loadCreateBidsInput(15L)).hasValueSatisfying(rows ->
                assertThat(rows).singleElement().satisfies(saved -> {
                    assertThat(saved.getStyleId()).isEqualTo("100289469");
                    assertThat(saved.getSize()).isEqualTo("US M 4.5");
                    assertThat(saved.getPrice()).isEqualByComparingTo("1");
                }));
    }

    @Test
    void roundTripsUpdateBidsInput() {
        TaskInputSnapshotStore store = new TaskInputSnapshotStore(tempDir);
        StockXBidUpdateInputExcel row = new StockXBidUpdateInputExcel();
        row.setBidId("bid-1");
        row.setPrice(new BigDecimal("88"));

        store.saveUpdateBidsInput(16L, List.of(row));

        assertThat(store.loadUpdateBidsInput(16L)).hasValueSatisfying(rows ->
                assertThat(rows).singleElement().satisfies(saved -> {
                    assertThat(saved.getBidId()).isEqualTo("bid-1");
                    assertThat(saved.getPrice()).isEqualByComparingTo("88");
                }));
    }

    @Test
    void legacySnapshotWithoutTypeDefaultsToDefault() throws Exception {
        TaskInputSnapshotStore store = new TaskInputSnapshotStore(tempDir);
        Path taskDir = tempDir.resolve("11");
        Files.createDirectories(taskDir);
        Files.writeString(taskDir.resolve("price-down.json"),
                "{\"SKU-LEGACY:42\":{\"minPrice\":125,\"skip\":false}}");

        assertThat(store.loadPriceDown(11L)).hasValueSatisfying(input ->
                assertThat(input.get("SKU-LEGACY:42").type()).isEqualTo(PriceDownType.DEFAULT));
    }
}
