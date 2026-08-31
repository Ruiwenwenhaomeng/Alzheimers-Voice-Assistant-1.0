# 异步音频筛查与多线程改造实施说明

本文记录 `async-screening-rabbitmq-design.md` 在当前项目中的落地结果、启用方式和运维边界。当前实现覆盖设计中的异步任务主链路、可靠事件投递、Python 后台处理、Java 后台生成 PDF、任务查询与前端轮询。

## 1. 已实现的数据链路

```text
用户选择音频并确认 AI 筛查
  -> Java 创建 screening_task，并在同一事务写入 outbox_event
  -> OutboxPublisher 通过 RabbitMQ 发布 screening.requested.v1
  -> Python Worker 消费任务
       -> 音频质量检查
       -> Whisper 转录
       -> 特征提取
       -> DeepSeek 分析
       -> 原子写入带 SHA-256 的 JSON 产物
       -> 发布阶段状态和 screening.analysis.completed.v1
  -> Java Result Listener 校验任务归属、产物路径与 SHA-256
       -> 保存 diagnosis_report
       -> 写入 pdf.requested.v1 Outbox 事件
  -> Java PDF Listener 后台生成并原子落盘 PDF
       -> 保存 pdf_report（含 SHA-256、文件大小）
       -> 将任务标记为 COMPLETED
  -> 用户重新登录或轮询任务列表，查看结果并下载 PDF
```

HTTP 请求只负责受理任务，不再等待 Whisper、特征提取、大模型和 PDF。旧同步诊断接口仍保留用于迁移回退，但当前页面已经改用异步入口。

## 2. Java 后端实现

### 2.1 任务和可靠投递

- `screening_task` 保存任务状态、阶段、进度、错误、重试次数和 trace ID。
- 创建任务与写入 `outbox_event` 使用同一数据库事务，避免数据库成功而消息丢失。
- `OutboxPublisher` 定时批量发布消息，并等待 RabbitMQ publisher confirm；失败时回退重试。
- `consumed_event` 按“消费者名称 + event ID”去重，消费者事务失败时去重记录也会回滚。
- 用户幂等键和音频任务唯一约束共同处理重复提交和并发竞争。

### 2.2 隔离线程池

Java RabbitMQ 消费链路使用三个有界线程池，互不占用 Tomcat 请求线程：

| 链路 | 默认线程数 | 默认队列容量 | 线程名前缀 |
| --- | ---: | ---: | --- |
| 结果持久化 | 2 | 20 | `screening-result-` |
| 状态投影 | 2 | 20 | `screening-status-` |
| PDF 生成 | 1 | 20 | `screening-pdf-` |

线程池采用固定大小、有界等待队列和 `CallerRunsPolicy`。RabbitMQ 默认 `prefetch=1`，使单个消费者不会一次占有大量重任务。线程数可以通过 `.env` 调整，但 PDF 和大模型相关并发应按 CPU、内存及上游配额逐步压测。

### 2.3 HTTP 接口

- `POST /api/v1/audios/{audioId}/screenings`：创建任务，返回 `202 Accepted`。
- `POST /audio/screening/{audioName}`：兼容现有页面的异步创建入口，返回 `202 Accepted`。
- `GET /api/v1/screenings/{taskId}`：查询任务状态和完成后的结果/PDF 链接。
- `GET /api/v1/screenings?status=active&page=0&size=20`：查询当前用户任务。
- `DELETE /api/v1/screenings/{taskId}`：请求取消任务。
- `POST /api/v1/screenings/{taskId}/retry`：重试失败任务。

创建和重试请求应携带 `Idempotency-Key`。页面已自动生成该请求头，并在存在活动任务时每 5 秒轮询；退出页面或退出登录不会终止后台任务。

## 3. Python Worker 实现

入口为：

```powershell
python -m app.workers.combined
```

Worker 以独立进程运行，每个进程消费一个任务，RabbitMQ `prefetch=1`。`SCREENING_WORKER_CONCURRENCY` 控制 `start.ps1` 启动的进程数，当前限制为 1～4。多个 Worker 可并行服务多个用户，但单条音频内部按“转录 -> 特征 -> 大模型”顺序执行，避免同一任务的阶段依赖被破坏。

处理失败时，Worker 将消息发送到带 TTL 的重试队列；超过 `SCREENING_WORKER_MAX_RETRIES` 后发布失败状态并进入死信队列。已经生成且校验通过的任务产物会直接复用，避免消息重复投递造成重复大模型调用。

## 4. RabbitMQ 拓扑

- 主交换机：`alz.screening.events.x`
- 重试交换机：`alz.screening.retry.x`
- 死信交换机：`alz.screening.dlx`
- Python 请求队列：`alz.screening.transcription.q`
- Java 结果队列：`alz.screening.result.java.q`
- Java 状态队列：`alz.screening.status.java.q`
- Java PDF 队列：`alz.pdf.generate.java.q`
- Python 延迟重试队列：`alz.screening.transcription.retry.10s.q`
- 公共死信队列：`alz.screening.dlq`

消息只包含任务、用户、音频等 ID 和受控存储引用，不在 RabbitMQ 中传输音频、PDF 或完整分析内容。

## 5. 首次启用步骤

### 5.1 执行数据库迁移

已有数据库必须先执行：

```text
deploy/mysql/migrations/V003__async_screening_tasks.sql
```

可在 MySQL Workbench 中连接项目数据库后运行该文件。全新数据库使用更新后的 `deploy/mysql/init.sql` 即可。当前项目尚未引入 Flyway，因此不能跳过这一步，否则后端会因缺少任务表而失败。

V003 会先检查报告列、索引和外键是否已经存在，可以在中断后安全重跑。旧数据库的报告表可能使用 `utf8mb3`，迁移会为 UUID 外键列显式使用与 `screening_task.id` 一致的 `utf8mb4_0900_ai_ci`，避免 MySQL 3780 外键类型不兼容错误。

### 5.2 配置环境变量

从 `.env.example` 复制或合并以下配置，并至少修改生产环境密码：

```dotenv
SCREENING_ASYNC_ENABLED=true
RABBITMQ_HOST=localhost
RABBITMQ_PORT=5672
RABBITMQ_VHOST=/alz
RABBITMQ_USERNAME=alz_app
RABBITMQ_PASSWORD=请替换为非默认强密码
SCREENING_WORKER_CONCURRENCY=1
```

Java、Python 和 Compose 必须使用相同的 RabbitMQ vhost、用户名和密码。异步功能默认关闭，便于完成数据库迁移和 RabbitMQ 部署后再显式启用。

先安装更新后的 Python 依赖（其中包含 `pika`）：

```powershell
python -m pip install -r screening-service\requirements.txt
```

也可以设置 `PYTHON_AUTO_VENV=true`，让 `start.ps1` 创建并维护项目内的 `screening-service\.venv`。启动脚本会预检 `pika`，缺少依赖时不会继续启动异步后端。

### 5.3 启动 RabbitMQ 和应用

```powershell
docker compose up -d rabbitmq
.\start.ps1
```

RabbitMQ AMQP 默认端口为 `5672`，管理页面默认为 `http://localhost:15672`。`start.ps1` 在异步开关打开时会检查 RabbitMQ，并自动启动配置数量的 Python Worker；RabbitMQ 不可用时会停止启动，避免任务被受理后长期无人消费。

## 6. 并发与故障保护

当前已经提供：

- 每个用户默认最多 2 个活动任务；超过限制返回 HTTP 429。
- 幂等键、数据库唯一约束和消费者去重。
- 有界 Java 线程池、RabbitMQ prefetch 和 Python Worker 并发上限。
- Publisher confirm、Outbox 重试、Python 延迟重试和死信队列。
- 任务状态单向推进、取消检查点、产物路径限制和 SHA-256 校验。
- PDF 服务端生成，前端不再上传可篡改的报告正文。

当前尚未实现 Redis 分布式令牌桶、基于队列深度的动态入口限流、Resilience4j 熔断器、独立的转录/特征/大模型三个 Python 队列、Worker 心跳与自动任务回收。这些属于设计文档后续阶段；上线前应先根据真实硬件和大模型配额压测，再决定拆分粒度和阈值。

## 7. 验证记录

- Java：`mvnw.cmd test`，58 个测试通过。
- Python：异步事件契约和产物存储测试，3 个测试通过。
- Python：`app` 全量字节码编译通过。
- `application.yml` 与 `compose.yaml` YAML 解析通过。
- `start.ps1` PowerShell 语法解析通过。
- `git diff --check` 通过。

当前开发机没有可用 Docker/RabbitMQ 实例，因此本轮没有执行真实 RabbitMQ + MySQL + Whisper/DeepSeek 的端到端任务。完成 V003 迁移并启动 RabbitMQ 后，应使用一段短 WAV 先做单任务冒烟测试，再逐步增加 Worker 并发。
