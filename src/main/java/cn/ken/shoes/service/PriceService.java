package cn.ken.shoes.service;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.manager.PriceManager;
import cn.ken.shoes.mapper.PoisonItemMapper;
import cn.ken.shoes.mapper.PoisonPriceMapper;
import cn.ken.shoes.model.entity.PoisonItemDO;
import cn.ken.shoes.model.entity.PoisonPriceDO;
import cn.ken.shoes.model.price.PriceVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PriceService {

    @Resource
    private PoisonClient poisonClient;

    @Resource
    private PoisonItemMapper poisonItemMapper;

    @Resource
    private PoisonPriceMapper poisonPriceMapper;

    @Resource
    private PriceManager priceManager;

    public Result<List<PriceVO>> queryByModelNo(String modelNo) {
        // 1. 查缓存价格
        List<PoisonPriceDO> cachedPrices = poisonPriceMapper.selectListByModelNos(Set.of(modelNo));
        Map<String, PoisonPriceDO> cachedMap = cachedPrices.stream()
                .collect(Collectors.toMap(PoisonPriceDO::getEuSize, p -> p, (a, b) -> b));

        // 2. 查实时价格
        Map<String, Integer> latestMap = queryLatestPrices(modelNo);

        // 3. 合并
        Set<String> allSizes = new TreeSet<>(cachedMap.keySet());
        allSizes.addAll(latestMap.keySet());

        if (allSizes.isEmpty()) {
            return Result.buildError("未找到该货号的得物价格");
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        List<PriceVO> result = new ArrayList<>();
        for (String euSize : allSizes) {
            PriceVO vo = new PriceVO();
            vo.setEuSize(euSize);

            PoisonPriceDO cached = cachedMap.get(euSize);
            if (cached != null) {
                vo.setCachedPrice(cached.getPrice());
                if (cached.getUpdateTime() != null) {
                    vo.setCacheTime(sdf.format(cached.getUpdateTime()));
                }
            }

            vo.setLatestPrice(latestMap.get(euSize));

            Integer businessPrice = priceManager.getPoisonPrice(modelNo, euSize);
            vo.setBusinessPrice(businessPrice);
            if (ShoesContext.isFlawsModel(modelNo, euSize)) {
                vo.setRemark("禁爬货号");
            } else if (ShoesContext.isNotCompareModel(modelNo, euSize)) {
                vo.setRemark("不比价货号");
            } else if (ShoesContext.isNoPrice(modelNo)) {
                vo.setRemark("无价缓存");
            }

            result.add(vo);
        }
        return Result.buildSuccess(result);
    }

    private Map<String, Integer> queryLatestPrices(String modelNo) {
        try {
            PoisonItemDO item = poisonItemMapper.selectByArticleNumber(modelNo);
            if (item == null) {
                List<PoisonItemDO> items = poisonClient.queryItemByModelNos(List.of(modelNo));
                if (CollectionUtils.isEmpty(items)) {
                    return Collections.emptyMap();
                }
                item = items.getFirst();
                poisonItemMapper.insert(item);
            }
            List<PoisonPriceDO> latestPrices = poisonClient.queryPriceBySpuV2(modelNo, item.getSpuId());
            if (CollectionUtils.isEmpty(latestPrices)) {
                return Collections.emptyMap();
            }
            return latestPrices.stream()
                    .collect(Collectors.toMap(PoisonPriceDO::getEuSize, PoisonPriceDO::getPrice, (a, b) -> b));
        } catch (Exception e) {
            log.error("queryLatestPrices error, modelNo:{}, msg:{}", modelNo, e.getMessage());
            return Collections.emptyMap();
        }
    }
}
