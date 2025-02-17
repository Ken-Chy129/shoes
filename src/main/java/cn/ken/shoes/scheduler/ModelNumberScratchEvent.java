package cn.ken.shoes.scheduler;

import cn.ken.shoes.model.item.ItemRequest;
import org.springframework.context.ApplicationEvent;

/**
 * 货号爬取事件
 */
public class ModelNumberScratchEvent extends ApplicationEvent {

    public ModelNumberScratchEvent(ItemRequest source) {
        super(source);
    }
}
