# StockX 寄存商品 Excel 改价

## 目标与成功标准

新增独立任务“修改寄存商品价格”，选定 StockX 账号后上传 Excel，
将指定寄存挂单改为表内目标售价；执行一轮，不自动压价或循环。
复用已有寄存列表、改价提交、限流、价格回查和任务明细机制。

用户已确认的口径：

- Excel 必填列为 `listingId`、`价格`。`listingId` 直接沿用
  “获取上架商品（寄存）”的导出列；用户新增“价格”列并填写目标售价。
- 价格为大于零的整数美元，不是利润、到手价或最低价。
- 其他导出列可保留但不参与匹配；不按货号和尺码批量匹配。
- 只处理 Excel 中列出的挂单，且必须属于所选账号当前的寄存列表。
- 价格相同跳过；非寄存、已售出或无法找到的挂单不提交，记录原因。
- 支持取消、进度、明细及结果导出；区分原价、目标价、已确认成功、
  失败与未确认，不能把上游“已受理”当成改价成功。
- 取消只停止后续请求，不能撤回已经提交的改价。

## 接口与边界

新增 `POST /api/task/stockx/startUpdateCustodialPrices`，使用 multipart：
`accountId` + `file`；沿用 `Result<String>` 返回任务 ID。
文件限 `.xls` / `.xlsx`、10MB；服务端在创建任务前校验所有输入行，
拒绝空文件、空 ID、重复 ID、空价、非正数、非整数及超出 32 位整数的价格。
不推断 ID，不截断价格，不跨账号调用写接口。

执行前完整读取所选账号的寄存列表并验证分页和数据；
无法确认查询完整时停止，不使用部分结果开始改价。
提交后按 listingId 回查实际价格，未确认同步的项明确记录。
同账号寄存自动压价与本任务不得同时运行，防止互相覆盖；
沿用已有取消及限流处理，不改变其他任务的配额策略。
默认不自动恢复或自动重跑写任务，需用户再次上传文件发起。

## 技术栈、结构与代码风格

Java 21 / Spring Boot / MyBatis-Plus / EasyExcel；
React / TypeScript / Ant Design。不增加依赖或数据库字段。
现有 `TaskItemDO` 已含 `currentPrice`、`targetPrice` 和 `listingId`。

沿用控制器约定：

```java
return taskId == null ? Result.buildError("任务已在运行或账号不存在")
        : Result.buildSuccess(String.valueOf(taskId));
```

主要调用点：

- `controller/TaskController`、`manager/TaskExecutorManager`、任务类型、
  `config/TaskSwitch`、`service/TaskService`：上传、任务生命周期和互斥。
- `client/StockXClient`、`manager/StockXPriceUpdateCoordinator`、
  `task/`：寄存校验、提交、回查；必要时仅增加窄范围的查询参数。
- `model/excel/`、`controller/TaskItemController`：输入及结果导出。
- `console/src/services/task.ts`、`pages/task/history/`、
  `pages/task/components/TaskItemModal.tsx`：任务入口、上传提示、提交确认及明细。
- Java 测试放在 `src/test/java/`，前端测试与现有任务页面测试同目录。

## 实现顺序与测试策略

1. 输入模型及上传校验：先测试合法文件、重复 ID、非法价格、文件限制。
2. 改价执行：模拟寄存分页、账号隔离、同价跳过、上游错误、部分失败、
   回查不一致、限流与取消；确保不会修改 Excel 外商品。
3. 生命周期：任务互斥、启动失败释放状态、取消及重启不自动执行。
4. UI 和导出：展示文件要求、账号及文件确认、原价/目标价/结果，
   输入条数保存到任务参数；
   本地模拟数据验证创建请求与明细；回归现有获取寄存商品和压价任务。
5. 验证后只提交本功能文件到 master 并推送；部署另行确认。

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -DskipTests package
npm --prefix console test -- --runInBand
npm --prefix console run tsc
npm --prefix console run build
git diff --check
```

## 范围限制

- 始终：验证输入、归属和上游响应，复用鉴权及限流，保留其他未提交改动。
- 先询问：变更上述 Excel 口径、增加依赖或数据库字段、真实账号改价验证、部署。
- 绝不：运行真实批量改价测试、处理现货或 Excel 外挂单、修改其他账号、
  自动下架或新建挂单、记录或提交凭证。
- 开始实施前，本地/origin master 为 `e6f6ae0f`，生产 master 为 `6c19b31e`。
  本次在 master 增量开发并推送；生产刻意保持原版本，不执行部署。

## 运行注意事项

- Excel 列名必须为 `listingId`、`价格`；其余列忽略，价格是纯数字美元。
- 同账号寄存自动压价需要先终止并等待退出，再启动手动改价。
- 本任务不提供历史重跑，重做时重新上传 Excel，避免复用过期目标价。
- 上游接受请求后，最多回查三次，每次间隔五秒；不能确认的项保留
  “未确认”结果，不当成成功或未执行，也不自动回滚。
- 取消、网络异常或重启不能撤回已发出的请求。再次操作前先用
  “获取上架商品（寄存）”核对真实价格。
- 寄存列表查询与改价之间不是上游原子事务；期间售出或状态变化的挂单
  可能被上游拒绝或无法回查，按失败/未确认处理，不创建新挂单。

## 本地验证（2026-11-13）

- 后端全量 779 项测试通过，后续补充了严格回查及非寄存库存拒绝测试。
- 前端 27 项测试、TypeScript 检查、前后端生产构建通过。
- 使用仅监听 localhost 的模拟服务验证：必填文件校验、Excel 上传、
  账号与文件二次确认、创建成功、原价/目标价/执行结果展示。
- 浏览器导出按钮已触发正确的任务导出请求；新窗口下载未被浏览器工具捕获，
  实际 XLSX 列和值由后端导出测试验证。
- 未部署、未连接真实 StockX 账号改价；部署前仍需独立检查 master 上其他功能的迁移要求。
