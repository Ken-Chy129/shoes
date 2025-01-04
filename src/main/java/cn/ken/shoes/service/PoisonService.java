package cn.ken.shoes.service;

import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.mapper.PoisonItemMapper;
import cn.ken.shoes.model.entity.PoisonItemDO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class PoisonService {

    @Resource
    private PoisonClient poisonClient;

    @Resource
    private PoisonItemMapper poisonItemMapper;

    public List<PoisonItemDO> scratchItems() {
        List<String> modelNoList = new ArrayList<>();
        modelNoList.add("112431102S-12");
        modelNoList.add("112431102S-10");
        modelNoList.add("112431805S-3");
        modelNoList.add("112441114-1");
        modelNoList.add("112511810S-2");
        List<PoisonItemDO> poisonItems = poisonClient.queryItemByModelNumber(modelNoList);
        poisonItemMapper.insert(poisonItems);
        return poisonItems;
    }
}
