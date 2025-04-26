package cn.ken.shoes.service;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.mapper.KickScrewItemMapper;
import cn.ken.shoes.mapper.MustCrawlMapper;
import cn.ken.shoes.mapper.PoisonItemMapper;
import cn.ken.shoes.model.shoes.ShoesRequest;
import cn.ken.shoes.model.shoes.ShoesVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ShoesService {

    @Resource
    private PoisonItemMapper poisonItemMapper;

    @Resource
    private KickScrewItemMapper kickScrewItemMapper;

    @Resource
    private MustCrawlMapper mustCrawlMapper;

    public PageResult<List<ShoesVO>> page(ShoesRequest request) {
        Long count = poisonItemMapper.shoesCount(request);
        if (count == 0) {
            return PageResult.buildSuccess();
        }
        List<ShoesVO> shoes = poisonItemMapper.shoes(request);
        PageResult<List<ShoesVO>> result = PageResult.buildSuccess(shoes);
        result.setTotal(count);
        return result;
    }

    public List<String> queryAllModels() {
        List<String> result = new ArrayList<>();
        // kc热门商品
        List<String> hotModelNos = kickScrewItemMapper.selectAllModelNos();
        result.addAll(hotModelNos);
        // 必爬商品
        List<String> mustCrawlModelNos = mustCrawlMapper.selectAllModelNos();
        result.addAll(mustCrawlModelNos);
        result = hotModelNos.stream().distinct().collect(Collectors.toList());
        // 移除瑕疵商品
        result.removeIf(ShoesContext::isFlawsModel);
        // 移除无价商品
        result.removeIf(ShoesContext::isNoPrice);
        log.info("queryAllModels, hotModelSize:{}, mustCrawlModelSize:{}, flawsModelSize:{}, noPriceModelSize:{}, finalModelSize:{}",
                hotModelNos.size(),
                mustCrawlModelNos.size(),
                ShoesContext.getFlawsModelSet().size(),
                ShoesContext.getNoPriceModelSet().size(),
                result.size()
        );
        return result;
    }
}
