package cn.ken.shoes.controller;

import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.common.*;
import cn.ken.shoes.config.KickScrewConfig;
import cn.ken.shoes.config.PoisonSwitch;
import cn.ken.shoes.config.PriceSwitch;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.mapper.BrandMapper;
import cn.ken.shoes.mapper.CustomModelMapper;
import cn.ken.shoes.mapper.MustCrawlMapper;
import cn.ken.shoes.model.brand.BrandRequest;
import cn.ken.shoes.model.entity.BrandDO;
import cn.ken.shoes.model.entity.CustomModelDO;
import cn.ken.shoes.model.entity.MustCrawlDO;
import cn.ken.shoes.model.excel.SpecialModelExcel;
import cn.ken.shoes.model.setting.PriceSetting;
import cn.ken.shoes.service.KickScrewService;
import cn.ken.shoes.util.SqlHelper;
import com.alibaba.excel.EasyExcel;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("setting")
public class SettingController {

    @Resource
    private KickScrewService kickScrewService;

    @Resource
    private BrandMapper brandMapper;

    @Resource
    private CustomModelMapper customModelMapper;

    @Resource
    private MustCrawlMapper mustCrawlMapper;

    @GetMapping("poison")
    public Result<JSONObject> queryPoisonSetting() {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("apiMode", PoisonSwitch.API_MODE);
        jsonObject.put("maxPrice", PoisonSwitch.MAX_PRICE);
        jsonObject.put("openImportDBData", PoisonSwitch.OPEN_IMPORT_DB_DATA);
        jsonObject.put("openNoPriceCache", PoisonSwitch.OPEN_NO_PRICE_CACHE);
        jsonObject.put("stopQueryPrice", PoisonSwitch.STOP_QUERY_PRICE);
        jsonObject.put("openAllThreeFive", PoisonSwitch.OPEN_ALL_THREE_FIVE);
        jsonObject.put("minProfit", PoisonSwitch.MIN_PROFIT);
        jsonObject.put("minThreeFiveProfit", PoisonSwitch.MIN_THREE_PROFIT);
        jsonObject.put("useV3Api", PoisonSwitch.USE_V3_API);
        jsonObject.put("useV4Api", PoisonSwitch.USE_V4_API);
        return Result.buildSuccess(jsonObject);
    }

    @PostMapping("poison")
    public Result<JSONObject> updatePoisonSetting(@RequestBody JSONObject jsonObject) {
        PoisonSwitch.API_MODE = jsonObject.getInteger("apiMode");
        PoisonSwitch.MAX_PRICE = jsonObject.getInteger("maxPrice");
        PoisonSwitch.OPEN_IMPORT_DB_DATA = jsonObject.getBoolean("openImportDBData");
        PoisonSwitch.OPEN_NO_PRICE_CACHE = jsonObject.getBoolean("openNoPriceCache");
        PoisonSwitch.STOP_QUERY_PRICE = jsonObject.getBoolean("stopQueryPrice");
        PoisonSwitch.OPEN_ALL_THREE_FIVE = jsonObject.getBoolean("openAllThreeFive");
        PoisonSwitch.MIN_PROFIT = jsonObject.getInteger("minProfit");
        PoisonSwitch.MIN_THREE_PROFIT = jsonObject.getInteger("minThreeFiveProfit");
        PoisonSwitch.USE_V3_API = jsonObject.getBooleanValue("useV3Api");
        PoisonSwitch.USE_V4_API = jsonObject.getBooleanValue("useV4Api");
        PoisonSwitch.saveConfig();
        return Result.buildSuccess(jsonObject);
    }

    @GetMapping("kc")
    public Result<PriceSetting> queryKcSetting() {
        PriceSetting priceSetting = new PriceSetting();
        priceSetting.setExchangeRate(PriceSwitch.EXCHANGE_RATE);
        priceSetting.setFreight(PriceSwitch.FREIGHT);
        priceSetting.setKcGetRate(PriceSwitch.KC_GET_RATE);
        priceSetting.setKcServiceFee(PriceSwitch.KC_SERVICE_FEE);
        return Result.buildSuccess(priceSetting);
    }

    @PostMapping("kc")
    public Result<Boolean> updateKcSetting(@RequestBody PriceSetting priceSetting) {
        PriceSwitch.EXCHANGE_RATE = priceSetting.getExchangeRate();
        PriceSwitch.FREIGHT = priceSetting.getFreight();
        PriceSwitch.KC_GET_RATE = priceSetting.getKcGetRate();
        PriceSwitch.KC_SERVICE_FEE = priceSetting.getKcServiceFee();
        PriceSwitch.saveConfig();
        return Result.buildSuccess(true);
    }

    @GetMapping("kc/getAuthorization")
    public Result<String> getKcAuthorization() {
        return Result.buildSuccess(KickScrewConfig.CONFIG.getAccessToken());
    }

    @PostMapping("kc/updateAuthorization")
    public Result<Boolean> updateKcAuthorization(@RequestBody JSONObject jsonObject) {
        KickScrewConfig.CONFIG.setAccessToken(jsonObject.getString("accessToken"));
        KickScrewConfig.saveOAuthConfig();
        return Result.buildSuccess(true);
    }

    @GetMapping("queryBrandSetting")
    public PageResult<List<BrandDO>> queryBrandSetting(BrandRequest request) {
        Long count = brandMapper.count(request);
        if (count <= 0) {
            return PageResult.buildSuccess();
        }
        List<BrandDO> brandDOList = brandMapper.selectPage(request);
        PageResult<List<BrandDO>> result = PageResult.buildSuccess(brandDOList);
        result.setTotal(count);
        return result;
    }

    @PostMapping("updateBrandSetting")
    public Result<Boolean> updateBrandSetting(@RequestBody BrandDO brandDO) {
        brandMapper.updateByName(brandDO);
        return Result.buildSuccess(Boolean.TRUE);
    }

    @GetMapping("queryMustCrawlModelNos")
    public Result<List<String>> queryMustCrawlModelNos() {
        return Result.buildSuccess(kickScrewService.queryMustCrawlModelNos());
    }

    @PostMapping("updateMustCrawlModelNos")
    public Result<Boolean> updateMustCrawlModelNos(@RequestBody JSONObject jsonObject) {
        String modelNos = Optional.ofNullable(jsonObject.getString("modelNos")).orElse("");
        List<String> modelNoList = Arrays.stream(modelNos.split(",")).filter(StrUtil::isNotBlank).map(String::trim).toList();
        kickScrewService.updateMustCrawlModelNos(modelNoList);
        return Result.buildSuccess(true);
    }

    @GetMapping("queryCustomModelNos")
    public Result<List<String>> queryCustomModelNos(int type) {
        List<CustomModelDO> customModelDOS = customModelMapper.selectByType(type);
        List<String> result = customModelDOS.stream().map(customModelDO -> {
            String modelNo = customModelDO.getModelNo();
            String euSize = customModelDO.getEuSize();
            if (StrUtil.isBlank(euSize)) {
                return modelNo;
            }
            return modelNo + ":" + euSize;
        }).toList();
        return Result.buildSuccess(result);
    }

    @PostMapping("updateCustomModelNos")
    public Result<Boolean> updateCustomModelNos(@RequestBody JSONObject jsonObject) {
        String modelNos = Optional.ofNullable(jsonObject.getString("modelNos")).orElse("");
        CustomPriceTypeEnum type = CustomPriceTypeEnum.from(jsonObject.getInteger("type"));
        if (type == null) {
            return Result.buildSuccess(false);
        }
        List<String> modelList = Arrays.stream(modelNos.split(",")).filter(StrUtil::isNotBlank).map(String::trim).toList();
        List<CustomModelDO> toInsert = modelList.stream().filter(StrUtil::isNotBlank).map(model -> {
            CustomModelDO customModelDO = new CustomModelDO();
            customModelDO.setType(type.getCode());
            String[] split = model.split(":");
            customModelDO.setModelNo(split[0]);
            if (split.length == 2) {
                customModelDO.setEuSize(split[1]);
            }
            return customModelDO;
        }).toList();

        type.getClearRunnable().run();
        customModelMapper.clearByType(type.getCode());
        if (CollectionUtils.isEmpty(toInsert)) {
            return Result.buildSuccess(true);
        }
        toInsert.forEach(model -> type.getCachePutConsumer().accept(model));
        SqlHelper.batch(toInsert, modelDO -> customModelMapper.insertIgnore(modelDO));
        return Result.buildSuccess(true);
    }

    @PostMapping("updateDefaultCrawlCnt")
    public Result<Boolean> updateDefaultCrawlCnt(@RequestBody JSONObject jsonObject) {
        Integer defaultCnt = jsonObject.getInteger("defaultCnt");
        String platform = Optional.ofNullable(jsonObject.getString("platform")).orElse("kc");
        if (defaultCnt == null) {
            return Result.buildSuccess(Boolean.FALSE);
        }
        brandMapper.updateDefaultCrawlCnt(defaultCnt, platform);
        return Result.buildSuccess(true);
    }

    // ==================== StockX 多账号管理 ====================

    @GetMapping("stockx/accounts")
    public Result<List<StockXAccount>> getStockXAccounts() {
        return Result.buildSuccess(StockXConfig.getAccounts());
    }

    @PostMapping("stockx/accounts")
    public Result<Boolean> addStockXAccount(@RequestBody StockXAccount account) {
        if (StrUtil.isBlank(account.getName())) {
            return Result.buildError("name不能为空");
        }
        if (StockXConfig.getAccount(account.getName()) != null) {
            return Result.buildError("账号名称已存在: " + account.getName());
        }
        StockXConfig.addAccount(account);
        return Result.buildSuccess(true);
    }

    @PutMapping("stockx/accounts/{name}")
    public Result<Boolean> updateStockXAccount(@PathVariable String name, @RequestBody StockXAccount account) {
        if (StockXConfig.getAccount(name) == null) {
            return Result.buildError("账号不存在: " + name);
        }
        account.setName(name);
        StockXConfig.updateAccount(account);
        return Result.buildSuccess(true);
    }

    @DeleteMapping("stockx/accounts/{name}")
    public Result<Boolean> deleteStockXAccount(@PathVariable String name) {
        if (StockXConfig.getAccount(name) == null) {
            return Result.buildError("账号不存在: " + name);
        }
        if (TaskSwitch.isExcelRunning(name, "STANDARD") || TaskSwitch.isExcelRunning(name, "CUSTODIAL")) {
            return Result.buildError("该账号有正在运行的压价任务，请先终止");
        }
        StockXConfig.removeAccount(name);
        return Result.buildSuccess(true);
    }

    // ==================== 特殊货号统一管理 ====================

    private static final Map<String, Integer> CATEGORY_TYPE_MAP = Map.of(
            "forbidden", 4,
            "notCompare", 2
    );

    @GetMapping("specialModelPage")
    public PageResult<List<Map<String, String>>> specialModelPage(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String modelNo,
            @RequestParam(defaultValue = "1") int pageIndex,
            @RequestParam(defaultValue = "20") int pageSize) {

        List<Map<String, String>> resultList;
        long total = 0;
        int startIndex = (pageIndex - 1) * pageSize;

        if (category == null || category.isEmpty()) {
            long mustCount = mustCrawlMapper.countByPlatform("kc", modelNo);
            long forbiddenCount = customModelMapper.countByType(4, modelNo);
            long notCompareCount = customModelMapper.countByType(2, modelNo);
            total = mustCount + forbiddenCount + notCompareCount;

            List<Map<String, String>> all = new ArrayList<>();
            for (MustCrawlDO m : mustCrawlMapper.pageByPlatform("kc", modelNo, 0, (int) mustCount)) {
                all.add(Map.of("modelNo", m.getModelNo(), "euSize", "", "category", "mustCrawl"));
            }
            for (CustomModelDO m : customModelMapper.pageByType(4, modelNo, 0, (int) forbiddenCount)) {
                all.add(Map.of("modelNo", m.getModelNo(), "euSize", Optional.ofNullable(m.getEuSize()).orElse(""), "category", "forbidden"));
            }
            for (CustomModelDO m : customModelMapper.pageByType(2, modelNo, 0, (int) notCompareCount)) {
                all.add(Map.of("modelNo", m.getModelNo(), "euSize", Optional.ofNullable(m.getEuSize()).orElse(""), "category", "notCompare"));
            }
            int end = Math.min(startIndex + pageSize, all.size());
            resultList = startIndex < all.size() ? new ArrayList<>(all.subList(startIndex, end)) : new ArrayList<>();
        } else if ("mustCrawl".equals(category)) {
            total = mustCrawlMapper.countByPlatform("kc", modelNo);
            resultList = new ArrayList<>();
            for (MustCrawlDO m : mustCrawlMapper.pageByPlatform("kc", modelNo, startIndex, pageSize)) {
                resultList.add(Map.of("modelNo", m.getModelNo(), "euSize", "", "category", "mustCrawl"));
            }
        } else if (CATEGORY_TYPE_MAP.containsKey(category)) {
            int type = CATEGORY_TYPE_MAP.get(category);
            total = customModelMapper.countByType(type, modelNo);
            resultList = new ArrayList<>();
            for (CustomModelDO m : customModelMapper.pageByType(type, modelNo, startIndex, pageSize)) {
                resultList.add(Map.of("modelNo", m.getModelNo(), "euSize", Optional.ofNullable(m.getEuSize()).orElse(""), "category", category));
            }
        } else {
            resultList = new ArrayList<>();
        }

        PageResult<List<Map<String, String>>> result = PageResult.buildSuccess(resultList);
        result.setTotal(total);
        return result;
    }

    @PostMapping("addSpecialModel")
    public Result<Integer> addSpecialModel(@RequestBody JSONObject body) {
        String category = body.getString("category");
        String modelNos = body.getString("modelNos");
        if (StrUtil.isBlank(category) || StrUtil.isBlank(modelNos)) {
            return Result.buildError("category和modelNos不能为空");
        }
        List<String> lines = Arrays.stream(modelNos.split("\n")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        int count = 0;
        if ("mustCrawl".equals(category)) {
            for (String line : lines) {
                MustCrawlDO crawlDO = new MustCrawlDO();
                crawlDO.setPlatform("kc");
                crawlDO.setModelNo(line.split(":")[0].trim());
                mustCrawlMapper.insertIgnore(crawlDO);
                count++;
            }
        } else if (CATEGORY_TYPE_MAP.containsKey(category)) {
            int type = CATEGORY_TYPE_MAP.get(category);
            for (String line : lines) {
                CustomModelDO customModelDO = new CustomModelDO();
                customModelDO.setType(type);
                String[] parts = line.split(":");
                customModelDO.setModelNo(parts[0].trim());
                if (parts.length >= 2 && !parts[1].trim().isEmpty()) {
                    customModelDO.setEuSize(parts[1].trim());
                }
                customModelMapper.insertIgnore(customModelDO);
                count++;
            }
        } else {
            return Result.buildError("无效的category: " + category);
        }
        return Result.buildSuccess(count);
    }

    @DeleteMapping("deleteSpecialModel")
    public Result<Boolean> deleteSpecialModel(@RequestParam String category,
                                               @RequestParam String modelNo,
                                               @RequestParam(required = false) String euSize) {
        if ("mustCrawl".equals(category)) {
            mustCrawlMapper.deleteByPlatformAndModelNo("kc", modelNo);
        } else if (CATEGORY_TYPE_MAP.containsKey(category)) {
            int type = CATEGORY_TYPE_MAP.get(category);
            customModelMapper.deleteByTypeAndModelNo(type, modelNo, euSize);
        } else {
            return Result.buildError("无效的category: " + category);
        }
        return Result.buildSuccess(true);
    }

    @PostMapping("importSpecialModelExcel")
    public Result<Integer> importSpecialModelExcel(@RequestParam("file") MultipartFile file,
                                                    @RequestParam("category") String category) throws IOException {
        if (StrUtil.isBlank(category)) {
            return Result.buildError("category不能为空");
        }
        List<SpecialModelExcel> list = EasyExcel.read(file.getInputStream())
                .head(SpecialModelExcel.class).sheet().doReadSync();
        if (CollectionUtils.isEmpty(list)) {
            return Result.buildSuccess(0);
        }
        int count = 0;
        if ("mustCrawl".equals(category)) {
            for (SpecialModelExcel row : list) {
                if (StrUtil.isBlank(row.getModelNo())) continue;
                MustCrawlDO crawlDO = new MustCrawlDO();
                crawlDO.setPlatform("kc");
                crawlDO.setModelNo(row.getModelNo().trim());
                mustCrawlMapper.insertIgnore(crawlDO);
                count++;
            }
        } else if (CATEGORY_TYPE_MAP.containsKey(category)) {
            int type = CATEGORY_TYPE_MAP.get(category);
            for (SpecialModelExcel row : list) {
                if (StrUtil.isBlank(row.getModelNo())) continue;
                CustomModelDO customModelDO = new CustomModelDO();
                customModelDO.setType(type);
                customModelDO.setModelNo(row.getModelNo().trim());
                if (StrUtil.isNotBlank(row.getEuSize())) {
                    customModelDO.setEuSize(row.getEuSize().trim());
                }
                customModelMapper.insertIgnore(customModelDO);
                count++;
            }
        } else {
            return Result.buildError("无效的category: " + category);
        }
        return Result.buildSuccess(count);
    }

    @GetMapping("specialModelTemplate")
    public void specialModelTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment;filename=" +
                URLEncoder.encode("特殊货号导入模板.xlsx", StandardCharsets.UTF_8));
        EasyExcel.write(response.getOutputStream(), SpecialModelExcel.class).sheet("模板").doWrite(List.of());
    }
}
