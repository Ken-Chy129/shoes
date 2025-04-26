package cn.ken.shoes.service;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.mapper.KickScrewItemMapper;
import cn.ken.shoes.mapper.MustCrawlMapper;
import cn.ken.shoes.mapper.PoisonItemMapper;
import cn.ken.shoes.model.shoes.ShoesRequest;
import cn.ken.shoes.model.shoes.ShoesVO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

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
        List<String> hotModelNos = kickScrewItemMapper.selectAllModelNos();
        List<String> mustCrawlModelNos = mustCrawlMapper.queryByPlatformList("kc");
        hotModelNos.addAll(mustCrawlModelNos);
        List<String> modelNoList = hotModelNos.stream().distinct().collect(Collectors.toList());
        modelNoList.removeIf(ShoesContext::isFlawsModel);
        return modelNoList;
    }
}
