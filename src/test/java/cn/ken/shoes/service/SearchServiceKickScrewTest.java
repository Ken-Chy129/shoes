package cn.ken.shoes.service;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.mapper.SearchTaskMapper;
import cn.ken.shoes.model.entity.SearchTaskDO;
import cn.ken.shoes.model.excel.KickScrewSearchExcel;
import cn.ken.shoes.model.kickscrew.KickScrewKeywordSearchPage;
import com.alibaba.excel.EasyExcel;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SearchServiceKickScrewTest {

    @Test
    void keywordSearchPagesByCursorAndDedupesModelNos() throws Exception {
        SearchTaskDO task = new SearchTaskDO();
        task.setId(7001L);
        task.setPlatform("kickscrew");
        task.setQuery("jordan 1 chicago");
        task.setSorts("relevance,best_selling");
        task.setPageCount(3);
        task.setType("keyword");
        task.setSearchType("shoes");

        AtomicReference<String> savedFilePath = new AtomicReference<>();
        List<String> statusUpdates = new ArrayList<>();
        List<Integer> progress = new ArrayList<>();
        List<String> calls = new ArrayList<>();

        KickScrewClient client = new KickScrewClient() {
            @Override
            public KickScrewKeywordSearchPage searchByKeyword(String keyword, String sort, String searchType, String cursor) {
                calls.add(sort + ":" + cursor);
                if (cursor == null) {
                    return new KickScrewKeywordSearchPage(List.of(
                            item("HQ6998-600"), item("hq6998-600"), item(null)), true, "c1");
                }
                return new KickScrewKeywordSearchPage(List.of(item("DZ5485-612")), false, null);
            }
        };

        SearchService service = new SearchService();
        SearchTaskMapper mapper = (SearchTaskMapper) Proxy.newProxyInstance(
                SearchTaskMapper.class.getClassLoader(), new Class<?>[]{SearchTaskMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectById" -> task;
                    case "updateProgress" -> {
                        progress.add((Integer) args[1]);
                        yield null;
                    }
                    case "updateFilePath" -> {
                        savedFilePath.set((String) args[1]);
                        yield null;
                    }
                    case "updateStatus" -> {
                        statusUpdates.add((String) args[1]);
                        if (args[3] != null) {
                            savedFilePath.set((String) args[3]);
                        }
                        yield null;
                    }
                    default -> method.getReturnType() == int.class ? 0 : null;
                });
        setField(service, "searchTaskMapper", mapper);
        setField(service, "kickScrewClient", client);

        service.executeSearchTask(task.getId());

        // 每个排序第二页就没有下一页，不应继续请求第三页
        assertThat(calls).containsExactly("relevance:null", "relevance:c1", "best_selling:null", "best_selling:c1");
        assertThat(statusUpdates).containsExactly("success");
        assertThat(progress).last().isEqualTo(100);
        File saved = new File(savedFilePath.get());
        try {
            List<KickScrewSearchExcel> rows = EasyExcel.read(saved).head(KickScrewSearchExcel.class).sheet().doReadSync();
            assertThat(rows).extracting(KickScrewSearchExcel::getModelNo).containsExactly("HQ6998-600", "DZ5485-612");
            assertThat(rows.get(0).getUrl()).isEqualTo("https://www.kickscrew.com/products/h-HQ6998-600");
        } finally {
            if (!saved.delete()) {
                saved.deleteOnExit();
            }
        }
    }

    private static KickScrewKeywordSearchPage.Item item(String modelNo) {
        return new KickScrewKeywordSearchPage.Item(modelNo, "t " + modelNo, "Jordan", "Sneakers", "h-" + modelNo, true);
    }

    private static void setField(SearchService service, String name, Object value) throws Exception {
        Field field = SearchService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }
}
