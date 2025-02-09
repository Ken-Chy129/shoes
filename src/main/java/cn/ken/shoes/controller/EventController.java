package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.entity.EventDO;
import cn.ken.shoes.service.EventService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("event")
public class EventController {

    @Resource
    private EventService eventService;

    @GetMapping
    public Result<List<EventDO>> queryEvents() {
        return Result.buildSuccess(eventService.queryEvents());
    }
}
