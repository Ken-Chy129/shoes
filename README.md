# Shoes

面向球鞋运营的价格监控、商品资料管理与交易任务平台。

Shoes 将 KicksCrew、StockX、得物（Poison）和 eBay 的商品、尺码、价格及交易操作集中到一个管理台中，支持定时任务、批量 Excel、跨平台比价和运营配置。项目目前以内部运营工具为主，接入外部平台前请确认对应平台的 API 权限与使用条款。

## 能力概览

| 领域       | 能力                                                                    |
| ---------- | ----------------------------------------------------------------------- |
| 商品与价格 | 多平台商品资料、尺码转换、价格查询与缓存；支持按货号、品牌和平台管理    |
| KicksCrew  | 品牌/商品同步、尺码与价格抓取、库存和订单查询                           |
| StockX     | 商品搜索、价格抓取、上架/压价/下架、订单、竞价、补单和发货延期任务      |
| 得物       | 批量查价、POP/OAuth 配置和价格策略管理                                  |
| eBay       | OAuth 授权、商品资料补全、库存地点、批量刊登和账号删除通知回调          |
| 任务与文件 | 异步任务、暂停/恢复/重跑、任务明细、Excel 导入导出和执行结果追踪        |
| 管理台     | React + Ant Design Pro 控制台，统一管理商品、价格、任务、账号和系统配置 |

## 系统结构

```text
浏览器
  │
  ▼
console（React / Umi，开发端口 8000；生产由 Nginx 提供静态文件）
  │ /api 代理
  ▼
Spring Boot API（默认端口 8080）
  ├── MySQL：商品、价格、任务和配置
  ├── KicksCrew / StockX / 得物 / eBay 外部 API
  └── files/：上传文件、导出文件和运行时配置
```

### 技术栈

| 层         | 技术                                                                                     |
| ---------- | ---------------------------------------------------------------------------------------- |
| 后端       | Java 21、Spring Boot 3.4.1、MyBatis-Plus、MySQL 8、OkHttp、Hutool、Guava、JWT、EasyExcel |
| 前端       | React 18、UmiJS Max 4、Ant Design 5、TypeScript                                          |
| 自动化工具 | Node.js 18+、Playwright（可选，仅用于 `stockx-token-minter`）                            |
| 部署       | 后端 Dockerfile（JDK 21）；前端 Dockerfile + Nginx                                       |

## 快速开始

### 1. 环境准备

- Java 21
- Maven 3.9+
- MySQL 8+
- Node.js 18+
- pnpm 9+（前端 lockfile 为 v9）

### 2. 初始化数据库

新数据库执行基础表结构：

```bash
mysql -uroot -p -e \
  "CREATE DATABASE IF NOT EXISTS shoes DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -uroot -p shoes < src/main/resources/script/shoes.sql
```

`shoes.sql` 适用于新库。`src/main/resources/script/` 下按日期命名的 SQL 是历史增量迁移，已有数据库只在确认版本差异后按顺序执行，避免重复添加字段或重复建表。

### 3. 配置后端

复制配置模板并填写本地值：

```bash
cp src/main/resources/application.yml.example src/main/resources/application.yml
```

至少需要修改：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/shoes
    username: root
    password: your_password

api:
  token: replace-with-a-random-secret
```

平台配置按需填写：

| 配置段   | 用途                                 |
| -------- | ------------------------------------ |
| `poison` | 得物 POP、历史接口和批量查价配置     |
| `kc`     | KicksCrew API 配置                   |
| `stockx` | StockX API/OAuth、账号和代理配置     |
| `ebay`   | eBay 环境、OAuth、刊登策略和回调配置 |
| `proxy`  | 外部平台请求使用的代理（可选）       |

不要提交 `application.yml` 或任何真实 token、密码、cookie。运行时通过管理台保存的配置会写入 `files/config/`，该目录同样不应进入版本库。

### 4. 启动后端

项目编译配置包含 Java 21 preview flag；本机推荐使用脚本固定 JDK 21：

```bash
./build.sh spring-boot:run
```

如果当前 `JAVA_HOME` 已经是 JDK 21，也可以直接运行：

```bash
mvn spring-boot:run
```

后端默认地址为 `http://localhost:8080`。

### 5. 启动前端

```bash
cd console
pnpm install --frozen-lockfile
pnpm run dev
```

打开 `http://localhost:8000`。开发环境的 `/api/*` 请求会由 `console/config/proxy.ts` 转发到 `http://localhost:8080/*`。

当前后端用户服务仍是代码内的 mock 用户，默认登录凭据为 `admin / admin`；上线前请替换为真实的用户存储和密码策略。

## 常用命令

| 目录                   | 命令                                   | 说明                                |
| ---------------------- | -------------------------------------- | ----------------------------------- |
| 根目录                 | `./build.sh clean compile`             | 使用 JDK 21 编译后端                |
| 根目录                 | `./build.sh test`                      | 执行后端测试                        |
| 根目录                 | `./build.sh clean package -DskipTests` | 构建后端 jar                        |
| `console/`             | `pnpm run dev`                         | 启动前端开发服务器                  |
| `console/`             | `pnpm run build`                       | 构建前端生产资源                    |
| `console/`             | `pnpm run lint`                        | ESLint、Prettier 和 TypeScript 检查 |
| `console/`             | `pnpm test`                            | 执行前端测试                        |
| `stockx-token-minter/` | `npm test`                             | 执行 StockX token 工具测试          |

## Docker

构建后端镜像：

```bash
docker build -t shoes-backend .
```

运行时通过环境变量提供数据库和密钥；Spring Boot 会将 `API_TOKEN` 映射为 `api.token`：

```bash
docker run --rm --name shoes-backend \
  -p 8080:8080 \
  -e SPRING_DATASOURCE_URL='jdbc:mysql://host.docker.internal:3306/shoes?useSSL=false&allowPublicKeyRetrieval=true' \
  -e SPRING_DATASOURCE_USERNAME=root \
  -e SPRING_DATASOURCE_PASSWORD='your_password' \
  -e API_TOKEN='replace-with-a-random-secret' \
  -v "$PWD/files:/app/files" \
  -v "$PWD/logs:/app/log" \
  shoes-backend
```

前端镜像：

```bash
cd console
docker build -t shoes-console .
```

`console/nginx.conf` 默认将 API 转发到名为 `backend` 的容器（端口 8080），生产部署时请让前后端加入同一 Docker 网络，或按实际服务名调整 Nginx 配置。

## API 与鉴权

- 后端 Controller 的实际路径不带 `/api`，例如 `GET /user/current`；`/api` 只是前端开发代理前缀。
- 登录接口为 `POST /user/login`，返回 JWT；前端将 token 保存在 `sessionStorage`。
- 部分得物价格和 eBay 操作接口使用 `api-token` 请求头进行保护：

  ```bash
  curl -H "api-token: $API_TOKEN" \
    'http://localhost:8080/poison/price?modelNo=DD1391-100'
  ```

- API 结构可参考 [`console/config/oneapi.json`](console/config/oneapi.json)；外部平台的专项接口说明见下方文档索引。

## 项目结构

```text
src/main/java/cn/ken/shoes/
├── controller/      REST API
├── service/         业务逻辑
├── client/          外部平台客户端
├── mapper/          MyBatis 数据访问
├── model/           实体、请求响应和 Excel 模型
├── task/            任务执行器
├── scheduler/       定时调度
├── manager/         任务、价格和配置协调
└── config/          平台配置与开关
src/main/resources/
├── application.yml.example
├── config/           配置默认值
├── mapper/           MyBatis XML
└── script/           建表和数据库迁移 SQL
console/              React 管理控制台
stockx-token-minter/  StockX token 自动刷新工具
docs/specs/           业务操作规格说明
```

## 相关文档

- [StockX 搜索操作说明](docs/specs/model-search-operations.md)
- [StockX 采购操作说明](docs/specs/stockx-purchase-operations.md)
- [StockX token 工具说明](stockx-token-minter/README.md)
- [StockX token 新账号接入](stockx-token-minter/ONBOARDING.md)
- [得物 API 说明](poison-api.md)
- [得物价格 API 说明](poison-price-api.md)
- [StockX API 示例](stockx-api.json)

## 常见问题

### 编译时报 Java 21 与 preview 相关错误

不要直接使用系统默认 JDK，改用 `./build.sh <maven-args>`。脚本会自动选择本机安装的 JDK 21；如果找不到，请先安装 JDK 21。

### 前端页面请求 API 失败

确认后端运行在 `8080` 端口，并检查 `console/config/proxy.ts` 的 `dev.target`。生产构建不会使用该开发代理，需要由 Nginx 或网关完成反向代理。

### StockX token 过期或刷新失败

先阅读 [`stockx-token-minter/README.md`](stockx-token-minter/README.md)，首次使用需执行一次有头浏览器登录；日常可用 `node index.js --once` 验证单轮刷新。

## 开发约定

提交前建议至少执行：

```bash
./build.sh test
cd console && pnpm run lint && pnpm test
```

请保持提交聚焦、不要提交密钥或运行产物，并遵循仓库中的 [`AGENTS.md`](AGENTS.md) 与 [`CLAUDE.md`](CLAUDE.md) 约定。
