package cn.ken.shoes.manager;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.model.excel.StockXDelistInputExcel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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

        store.savePriceDown(10L, Map.of("SKU-1:42", new ShoesContext.PriceDownConfig(125, false)));
        store.saveDelist(10L, List.of(delist));

        assertThat(store.loadPriceDown(10L)).hasValueSatisfying(input ->
                assertThat(input.get("SKU-1:42")).isEqualTo(new ShoesContext.PriceDownConfig(125, false)));
        assertThat(store.loadDelist(10L)).hasValueSatisfying(input -> {
            assertThat(input).singleElement().satisfies(item -> {
                assertThat(item.getListingId()).isEqualTo("listing-1");
                assertThat(item.getStyleId()).isEqualTo("SKU-1");
            });
        });
    }

    @Test
    void missingTaskInputReturnsEmptyOptional() {
        TaskInputSnapshotStore store = new TaskInputSnapshotStore(tempDir);

        assertThat(store.loadPriceDown(99L)).isEmpty();
        assertThat(store.loadDelist(99L)).isEmpty();
    }
}
