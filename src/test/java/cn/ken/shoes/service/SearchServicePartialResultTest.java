package cn.ken.shoes.service;

import cn.hutool.core.lang.Pair;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.manager.PriceManager;
import cn.ken.shoes.mapper.SearchTaskMapper;
import cn.ken.shoes.model.entity.SearchTaskDO;
import cn.ken.shoes.model.excel.StockXPriceExcel;
import cn.ken.shoes.model.stockx.StockXAccount;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SearchServicePartialResultTest {

    @Test
    void cancelledKeywordSearchKeepsAlreadyCrawledRowsDownloadable() throws Exception {
        SearchTaskDO task = new SearchTaskDO();
        task.setId(91L);
        task.setPlatform("stockx");
        task.setQuery("jordan retro");
        task.setSorts("featured,lowest_ask");
        task.setPageCount(2);
        task.setType("keyword");
        task.setSearchType("shoes");

        AtomicReference<String> savedFilePath = new AtomicReference<>();
        List<String> statusUpdates = new ArrayList<>();
        SearchServiceTestSupport support = new SearchServiceTestSupport(task, savedFilePath, statusUpdates);
        SearchService service = support.service();

        // 第一次搜索返回数据后立刻取消，模拟"跑了很久被中断"
        support.onSearch(pageIndex -> {
            service.cancelSearchTask(task.getId());
            return List.of(price("IF4396-104", "42.5", 186));
        });

        service.executeSearchTask(task.getId());

        assertThat(savedFilePath.get()).as("中断时应已落盘部分结果").isNotNull();
        File saved = new File(savedFilePath.get());
        try {
            assertThat(saved).exists();
            assertThat(saved.length()).isPositive();
        } finally {
            deleteQuietly(saved);
        }
    }

    private static StockXPriceExcel price(String modelNo, String euSize, int amount) {
        StockXPriceExcel excel = new StockXPriceExcel();
        excel.setModelNo(modelNo);
        excel.setEuSize(euSize);
        excel.setPrice(amount);
        return excel;
    }

    private static void deleteQuietly(File file) {
        if (file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    /** 用轻量代理隔离 DB/HTTP，仅验证"中断时落盘"这一行为。 */
    private static class SearchServiceTestSupport {

        private final SearchService service = new SearchService();
        private final AtomicReference<java.util.function.Function<Integer, List<StockXPriceExcel>>> searchStub =
                new AtomicReference<>(pageIndex -> List.of());

        SearchServiceTestSupport(SearchTaskDO task, AtomicReference<String> savedFilePath,
                                 List<String> statusUpdates) throws Exception {
            SearchTaskMapper mapper = (SearchTaskMapper) Proxy.newProxyInstance(
                    SearchTaskMapper.class.getClassLoader(), new Class<?>[]{SearchTaskMapper.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "selectById" -> {
                                return task;
                            }
                            case "updateFilePath" -> {
                                savedFilePath.set((String) args[1]);
                                return null;
                            }
                            case "updateStatus" -> {
                                statusUpdates.add((String) args[1]);
                                if (args[3] != null) {
                                    savedFilePath.set((String) args[3]);
                                }
                                return null;
                            }
                            default -> {
                                Class<?> returnType = method.getReturnType();
                                if (returnType == int.class) return 0;
                                if (returnType == long.class) return 0L;
                                if (returnType == boolean.class) return false;
                                return null;
                            }
                        }
                    });
            StockXClient client = new StockXClient() {
                @Override
                public Pair<Integer, List<StockXPriceExcel>> searchItemWithPrice(
                        String query, Integer pageIndex, String sort, String searchType,
                        String country, StockXAccount account) {
                    return Pair.of(2, searchStub.get().apply(pageIndex));
                }
            };
            PriceManager priceManager = new PriceManager() {
                @Override
                public Integer getPoisonPrice(String modelNo, String euSize) {
                    return null;
                }
            };
            setField("searchTaskMapper", mapper);
            setField("stockXClient", client);
            setField("priceManager", priceManager);
        }

        void onSearch(java.util.function.Function<Integer, List<StockXPriceExcel>> stub) {
            searchStub.set(stub);
        }

        SearchService service() {
            return service;
        }

        private void setField(String name, Object value) throws Exception {
            Field field = SearchService.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(service, value);
        }
    }
}
