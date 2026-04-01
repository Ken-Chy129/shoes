# Shoes

全栈球鞋价格监控与管理平台，聚合 KickScrew、StockX、得物（Poison）等多个球鞋交易平台的数据，支持价格追踪、比价分析、定时任务抓取和 Excel 导出。

## 功能特性

- **多平台数据聚合**：对接 KickScrew、StockX、得物等平台 API，统一管理商品与价格数据
- **价格监控**：定时抓取各平台最新价格，支持自定义调度策略
- **尺码对照**：内置尺码转换工具，跨平台尺码统一映射
- **任务系统**：可配置的后台抓取任务，支持任务调度与执行管理
- **数据导出**：支持 Excel 导出价格、商品、订单等数据
- **管理控制台**：React + Ant Design Pro 前端，可视化管理商品、价格、任务和配置
- **JWT 鉴权**：Token 认证，接口访问控制

## 技术栈

**后端**：Java 21、Spring Boot 3.4、MyBatis-Plus、MySQL、OkHttp3、Guava、EasyExcel、JWT

**前端**：React 18、Ant Design Pro、UmiJS Max、TypeScript

## 项目结构

```
├── src/                  # 后端 Spring Boot 应用
│   ├── client/           # 各平台 API 客户端（KickScrew、StockX、得物）
│   ├── controller/       # REST API 接口
│   ├── service/          # 业务逻辑层
│   ├── mapper/           # MyBatis 数据访问层
│   ├── model/            # 实体、DTO、Excel 模型
│   ├── task/             # 后台抓取任务
│   ├── scheduler/        # 定时调度
│   ├── config/           # 配置与开关
│   └── util/             # 工具类（HTTP、代理、尺码转换等）
├── console/              # 前端管理控制台
├── Dockerfile            # Docker 构建文件
└── search_task.sql       # 数据库初始化脚本
```

## 快速开始

### 后端

```bash
# 初始化数据库
mysql -u root -p < search_task.sql

# 修改 application.yml 配置数据库、代理等信息后启动
mvn spring-boot:run
```

### 前端

```bash
cd console
pnpm install
pnpm run dev
```
