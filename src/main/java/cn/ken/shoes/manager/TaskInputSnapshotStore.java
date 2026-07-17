package cn.ken.shoes.manager;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.model.excel.StockXDelistInputExcel;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class TaskInputSnapshotStore {

    private static final String PRICE_DOWN_FILE = "price-down.json";
    private static final String DELIST_FILE = "delist.json";

    private final Path root;

    public TaskInputSnapshotStore() {
        this(Paths.get("files/task-input"));
    }

    TaskInputSnapshotStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public void savePriceDown(Long taskId, Map<String, ShoesContext.PriceDownConfig> input) {
        JSONObject data = new JSONObject(true);
        input.forEach((key, config) -> data.put(key, new JSONObject()
                .fluentPut("minPrice", config.minPrice())
                .fluentPut("skip", config.skip())));
        write(taskPath(taskId, PRICE_DOWN_FILE), data.toJSONString());
    }

    public Optional<Map<String, ShoesContext.PriceDownConfig>> loadPriceDown(Long taskId) {
        Path path = taskPath(taskId, PRICE_DOWN_FILE);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            JSONObject data = JSON.parseObject(Files.readString(path));
            Map<String, ShoesContext.PriceDownConfig> result = new LinkedHashMap<>();
            data.forEach((key, value) -> {
                JSONObject config = (JSONObject) value;
                result.put(key, new ShoesContext.PriceDownConfig(
                        config.getIntValue("minPrice"), config.getBooleanValue("skip")));
            });
            return Optional.of(result);
        } catch (Exception e) {
            throw new IllegalStateException("读取压价任务输入快照失败: " + taskId, e);
        }
    }

    public void saveDelist(Long taskId, List<StockXDelistInputExcel> input) {
        write(taskPath(taskId, DELIST_FILE), JSON.toJSONString(input));
    }

    public Optional<List<StockXDelistInputExcel>> loadDelist(Long taskId) {
        Path path = taskPath(taskId, DELIST_FILE);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            List<StockXDelistInputExcel> input = JSON.parseArray(Files.readString(path), StockXDelistInputExcel.class);
            return Optional.of(input != null ? List.copyOf(input) : List.of());
        } catch (Exception e) {
            throw new IllegalStateException("读取下架任务输入快照失败: " + taskId, e);
        }
    }

    private Path taskPath(Long taskId, String fileName) {
        if (taskId == null || taskId <= 0) {
            throw new IllegalArgumentException("非法任务ID");
        }
        return root.resolve(String.valueOf(taskId)).resolve(fileName).normalize();
    }

    private void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new IllegalStateException("保存任务输入快照失败: " + path.getFileName(), e);
        }
    }
}
