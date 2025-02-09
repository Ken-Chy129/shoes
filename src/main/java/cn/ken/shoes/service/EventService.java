package cn.ken.shoes.service;

import cn.ken.shoes.mapper.EventMapper;
import cn.ken.shoes.model.entity.EventDO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EventService {

    @Resource
    private EventMapper eventMapper;

    public List<EventDO> queryEvents() {
        return List.of();
    }
}
