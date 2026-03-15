package cn.ken.shoes.controller;

import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.excel.TaskItemExcel;
import com.alibaba.excel.EasyExcel;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("taskItem")
public class TaskItemController {

    @Resource
    private TaskItemMapper taskItemMapper;

    @GetMapping("page")
    public PageResult<List<TaskItemDO>> queryTaskItems(
            @RequestParam Long taskId,
            @RequestParam(required = false) Integer round,
            @RequestParam(required = false) String operateResult,
            @RequestParam(required = false) String styleId,
            @RequestParam(required = false) String euSize,
            @RequestParam(defaultValue = "1") Integer pageIndex,
            @RequestParam(defaultValue = "15") Integer pageSize) {
        int startIndex = (pageIndex - 1) * pageSize;
        List<TaskItemDO> items = taskItemMapper.selectByCondition(taskId, round, operateResult, styleId, euSize, startIndex, pageSize);
        Long total = taskItemMapper.countByCondition(taskId, round, operateResult, styleId, euSize);
        PageResult<List<TaskItemDO>> result = PageResult.buildSuccess(items);
        result.setTotal(total);
        return result;
    }

    @GetMapping("export")
    public void exportTaskItems(
            @RequestParam Long taskId,
            @RequestParam(required = false) Integer round,
            @RequestParam(required = false) String operateResult,
            @RequestParam(required = false) String styleId,
            @RequestParam(required = false) String euSize,
            HttpServletResponse response) throws IOException {
        // 查询所有符合条件的数据（不分页）
        List<TaskItemDO> items = taskItemMapper.selectByCondition(taskId, round, operateResult, styleId, euSize, 0, Integer.MAX_VALUE);

        // 转换为 Excel 模型
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        List<TaskItemExcel> excelList = new ArrayList<>();
        for (TaskItemDO item : items) {
            TaskItemExcel excel = new TaskItemExcel();
            excel.setRound(item.getRound());
            excel.setStyleId(item.getStyleId());
            excel.setSize(item.getSize());
            excel.setEuSize(item.getEuSize());
            excel.setCurrentPrice(item.getCurrentPrice() != null ? "$" + item.getCurrentPrice() : "-");
            excel.setLowestPrice(item.getLowestPrice() != null ? "$" + item.getLowestPrice() : "-");
            excel.setPoisonPrice(item.getPoisonPrice() != null ? "¥" + item.getPoisonPrice() : "-");
            excel.setPoison35Price(item.getPoison35Price() != null ? "¥" + item.getPoison35Price() : "-");
            excel.setProfit35(item.getProfit35() != null ? "$" + item.getProfit35().setScale(2, java.math.RoundingMode.HALF_UP) : "-");
            excel.setProfitRate35(item.getProfitRate35() != null ? String.format("%.2f%%", item.getProfitRate35().doubleValue() * 100) : "-");
            excel.setOperateResult(item.getOperateResult());
            excel.setOperateTime(item.getOperateTime() != null ? sdf.format(item.getOperateTime()) : "-");
            excelList.add(excel);
        }

        // 设置响应头
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = URLEncoder.encode("任务明细_" + taskId, StandardCharsets.UTF_8).replace("\\+", "%20");
        response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + fileName + ".xlsx");

        // 写入 Excel
        EasyExcel.write(response.getOutputStream(), TaskItemExcel.class)
                .sheet("任务明细")
                .doWrite(excelList);
    }
}
