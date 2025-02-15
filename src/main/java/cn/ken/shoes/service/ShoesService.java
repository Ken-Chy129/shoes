package cn.ken.shoes.service;

import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.mapper.PoisonItemMapper;
import cn.ken.shoes.model.shoes.ShoesRequest;
import cn.ken.shoes.model.shoes.ShoesVO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ShoesService {

    @Resource
    private PoisonItemMapper poisonItemMapper;

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
}
