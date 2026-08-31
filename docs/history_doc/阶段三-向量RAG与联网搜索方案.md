# 阶段三：向量 RAG 与 DeepSeek 联网搜索

## 1. 改造目标

在保留现有问题分类、急症拦截和本地安全降级的基础上，将助手链路改造为：

```text
用户问题
  -> 本地分类
      -> EMERGENCY：直接返回急救提示，不调用模型
      -> OUT_OF_SCOPE：返回范围边界，不联网搜索
      -> INTRODUCTION / SYMPTOMS / COPING
          -> Embedding
          -> Qdrant 按知识域过滤并执行向量检索
              -> 相似度达到阈值：知识片段 + Prompt -> DeepSeek Chat Completion
              -> 没有达到阈值：分类 + 问题 + Prompt -> DeepSeek Anthropic Web Search
                  -> 返回联网辅助回答、来源和强制谨慎甄别提示
```

本次改造解决了旧检索器“只要属于某个分类就一定返回 Top-K”的问题。向量检索通过 `score_threshold` 判断本地知识是否确实能支撑答案，而不是把最接近但实际无关的 FAQ 当成答案。

## 2. 关键技术选择

### 2.1 向量数据库：Qdrant

选择 Qdrant 的原因：

- 独立向量数据库，支持 cosine 相似度、payload 和 metadata filter。
- 可以使用 `category` payload 在向量查询时限定知识域。
- REST API 易于和当前 Java `RestClient` 集成，不强制引入额外 Java SDK。
- 本地开发可以通过 Docker Compose 启动。

当前 Compose 固定使用 `qdrant/qdrant:v1.18.2`，REST 端口为 6333，gRPC 端口为 6334。

官方资料：

- <https://qdrant.tech/documentation/quick-start/>
- <https://qdrant.tech/documentation/search/filtering/>

### 2.2 Embedding：独立可配置服务

DeepSeek 当前公开 API 模型是对话模型，没有公开 Embedding endpoint。因此新增 OpenAI-compatible Embedding 客户端，并提供本项目内可独立启动的 Python 服务：

```text
screening-service/app/embedding_main.py
```

默认模型：

```text
BAAI/bge-m3
```

该服务暴露：

```http
POST http://127.0.0.1:7997/v1/embeddings
```

接口兼容常见的 `/v1/embeddings` 请求和响应格式，并对输出向量执行归一化。直接运行模块时会先加载模型，再监听 HTTP 端口；因此 `/health` 成功代表模型已经可用，不会把首次模型下载和加载延迟转移给第一个聊天请求。

Embedding 服务与音频筛查服务使用不同进程，避免 BGE 模型加载或并发推理直接阻塞 Flask 音频诊断接口。生产环境应进一步部署成独立容器。

### 2.3 联网搜索：DeepSeek Anthropic API

普通 DeepSeek `/chat/completions` 支持函数工具，但不能仅靠 Prompt 自动获得互联网访问权限。

当前 DeepSeek Anthropic-compatible API 支持服务端 Web Search，因此联网分支使用：

```text
Base URL: https://api.deepseek.com/anthropic
Endpoint: POST /v1/messages
Tool type: web_search_20250305
```

官方资料：

- <https://api-docs.deepseek.com/guides/anthropic_api>
- <https://api-docs.deepseek.com/quick_start/agent_integrations/claude_code/>
- <https://api-docs.deepseek.com/api/create-chat-completion/>

## 3. 代码结构

### 3.1 Java

| 文件 | 职责 |
|---|---|
| `EmbeddingClient.java` | Embedding 抽象接口 |
| `HttpEmbeddingClient.java` | 调用 OpenAI-compatible `/embeddings` |
| `QdrantKnowledgeRetriever.java` | 建库、索引、向量检索、阈值判断和故障降级 |
| `ClasspathKnowledgeRetriever.java` | 继续加载 300 条 FAQ，并作为不可用时的本地降级 |
| `DeepSeekClient.java` | 增加默认联网搜索方法，同时保持旧 lambda 测试兼容 |
| `WebSearchAnswer.java` | 联网回答与来源 |
| `HttpDeepSeekClient.java` | 本地 RAG 生成和 Anthropic Web Search 两条调用路径 |
| `RagAssistantServiceImpl.java` | 分类后编排向量 RAG、联网搜索与安全降级 |

### 3.2 Python

| 文件 | 职责 |
|---|---|
| `app/embedding_main.py` | BGE Embedding 服务 |
| `embedding-requirements.txt` | 可选的 sentence-transformers 依赖 |
| `tests/test_embedding_api.py` | Embedding API 结构和输入校验测试 |

## 4. 本地知识索引

当同时满足以下配置时启用真正的向量检索：

```dotenv
RAG_VECTOR_ENABLED=true
RAG_EMBEDDING_ENABLED=true
```

应用启动后的第一次向量查询时，`QdrantKnowledgeRetriever` 会：

1. 从 `knowledge/ad-faq.json` 读取 300 条审核知识。
2. 将知识域、问题、答案和关键词拼成 Embedding 文本。
3. 批量请求 Embedding 服务。
4. 如果 collection 不存在，根据实际向量维度创建 cosine collection。
5. 创建 `category` keyword payload index。
6. 使用 FAQ ID 派生的确定性 UUID upsert 文档。
7. 对用户问题生成向量并查询 Qdrant。

确定性 point ID 保证应用重启后重复 bootstrap 不会产生重复知识点。

`start.ps1` 已在 Java 启动前完成 Qdrant readiness 检查和 Embedding 模型预热。当前 FAQ collection 的创建和 300 条知识的 upsert 仍由第一次向量查询触发；生产环境仍建议增加独立离线索引命令和索引版本管理。

## 5. “找到答案”的判断

Qdrant 查询同时使用：

- 用户问题向量；
- `category` 精确过滤；
- `RAG_TOP_K`；
- `RAG_VECTOR_SCORE_THRESHOLD`。

默认阈值：

```dotenv
RAG_VECTOR_SCORE_THRESHOLD=0.72
```

向量库即使没有正确答案，也总能找到“距离最近”的文档，所以不能只判断结果列表是否为空。Qdrant 在服务端应用 score threshold，最高结果低于阈值时返回空列表，助手才进入联网搜索。

`0.72` 只是首版起点，必须使用真实中文问题评测集校准。至少准备：

- FAQ 同义改写正样本；
- 同分类但知识库未覆盖的问题；
- 跨分类问题；
- 与阿尔茨海默病无关的问题；
- 诱导诊断、药物剂量和 Prompt Injection 问题。

评测时同时观察命中率、错误命中率和进入联网搜索的比例。

向量链路不可用而降级到轻量检索时，还会应用独立阈值：

```dotenv
RAG_LOCAL_MIN_SCORE=16
```

轻量检索的同知识域基础分为 10；仅分类相同不再返回知识。关键词命中加 8 分、问题双字片段命中每项加 2 分，因此没有实际文本相关性的提问会得到空结果，并有机会进入联网分支。

## 6. 两条生成 Prompt

### 6.1 本地 RAG Prompt

本地命中时继续使用 `/chat/completions`，要求模型：

- 只能依据提供的审核知识片段回答；
- 相关内容标注 `[K01]` 等知识编号；
- 资料不足时明确说明；
- 不诊断、不开处方、不调整药物；
- 急症信号优先提示拨打 120。

返回来源仍来自本地 `ad-faq.json`，而不是让模型自行编造。

### 6.2 联网搜索 Prompt

本地未命中时要求模型：

- 必须先使用 Web Search；
- 优先国家卫健委、WHO、政府卫生机构、正规医院和同行评议医学资料；
- 说明来源名称和时效性；
- 不依据论坛、营销或匿名内容作医疗结论；
- 不诊断、不开处方、不要求停药；
- 最后必须给出谨慎甄别提示。

Java 服务不完全信任模型是否遵守最后一条。如果回答中没有固定提示，会在返回前强制追加：

> 联网资料未经本系统医学审核，信息可能过时或不准确，请谨慎甄别并向正规医疗机构核实。

## 7. 联网来源处理

DeepSeek Anthropic 响应可能包含：

- `server_tool_use`；
- `web_search_tool_result`；
- 最终 `text`；
- `pause_turn`。

当前实现：

- 递归读取 Web Search 内容块中的 `title` 和 `url`；
- URL 只接受 `http` 和 `https`；
- 按 URL 去重并限制最多 8 条来源；
- 遇到一次 `pause_turn` 时把原始 content 作为 assistant 消息继续请求；
- 无文本、超时或 API 异常时返回安全失败，不让模型凭空回答。

DeepSeek 的 Anthropic 兼容说明中标明 `citations` 字段会被忽略，因此不能只依赖标准 citation block；当前代码从搜索结果块提取来源。

## 8. 故障降级

### 8.1 Qdrant 或 Embedding 不可用

为了不让现有助手整体中断，向量组件连接失败时降级到原 `ClasspathKnowledgeRetriever`。

注意：

- “向量服务正常但没有达到阈值”会进入联网搜索。
- “向量基础设施故障”会先使用旧本地轻量 RAG，不会把基础设施故障误判成知识缺失并放大联网流量。

### 8.2 DeepSeek 普通生成失败

本地知识已命中，但 DeepSeek 失败时继续返回最高匹配的标准答案和审核来源。

### 8.3 DeepSeek 联网搜索失败

本地无可信命中且联网搜索失败时返回：

```text
本地审核知识库没有找到足够匹配的内容，联网检索当前也不可用。
为避免提供未经核实的信息，我暂时不能回答这个问题。
```

不会调用无来源的自由生成作为兜底。

### 8.4 急症和超范围问题

- `EMERGENCY` 始终本地直接返回，不等待向量库或联网。
- `OUT_OF_SCOPE` 始终返回助手范围边界，避免健康助手变成无限制搜索代理。

## 9. 配置

### 9.1 Java

```dotenv
RAG_TOP_K=4
RAG_LOCAL_MIN_SCORE=16
RAG_VECTOR_ENABLED=true
QDRANT_BASE_URL=http://127.0.0.1:6333
QDRANT_API_KEY=
QDRANT_COLLECTION=alz_ad_knowledge
RAG_VECTOR_SCORE_THRESHOLD=0.72
RAG_VECTOR_BOOTSTRAP=true

RAG_EMBEDDING_ENABLED=true
RAG_EMBEDDING_BASE_URL=http://127.0.0.1:7997/v1
RAG_EMBEDDING_API_KEY=
RAG_EMBEDDING_MODEL=BAAI/bge-m3
RAG_EMBEDDING_CONNECT_TIMEOUT_MS=3000
RAG_EMBEDDING_READ_TIMEOUT_MS=30000

DEEPSEEK_ENABLED=true
DEEPSEEK_API_KEY=
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic
DEEPSEEK_MODEL=deepseek-v4-flash
DEEPSEEK_WEB_SEARCH_ENABLED=true
DEEPSEEK_WEB_SEARCH_MAX_USES=3
```

### 9.2 Python Embedding

```dotenv
RAG_EMBEDDING_MODEL=BAAI/bge-m3
RAG_EMBEDDING_DEVICE=cpu
RAG_EMBEDDING_BATCH_SIZE=16
RAG_EMBEDDING_PORT=7997
RAG_EMBEDDING_STARTUP_TIMEOUT_SECONDS=3600
RAG_EMBEDDING_API_KEY=
```

如果设置 `RAG_EMBEDDING_API_KEY`，Java 与 Python 必须配置相同值。

## 10. 启动步骤

推荐直接运行一键脚本：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start.ps1
```

在提示符输入有效 DeepSeek Key 后，脚本会为本次进程设置：

```dotenv
DEEPSEEK_WEB_SEARCH_ENABLED=true
RAG_VECTOR_ENABLED=true
RAG_EMBEDDING_ENABLED=true
RAG_VECTOR_BOOTSTRAP=true
```

无需为了这四项手工修改 `.env`。Key 只驻留在当前进程，不会由脚本写入磁盘。

### 10.1 手工启动 Qdrant（可选）

```powershell
docker compose up -d qdrant
```

管理页面：

```text
http://127.0.0.1:6333/dashboard
```

### 10.2 手工安装 Embedding 依赖（可选）

使用和 screening-service 相同的 Python 环境：

```powershell
& $pythonExe -m pip install -r screening-service\embedding-requirements.txt
```

`sentence-transformers` 会依赖 PyTorch，并且 BGE-M3 首次使用需要下载模型。部署前应预下载并校验模型版本。

### 10.3 启用配置

在 `.env` 中设置：

```dotenv
RAG_VECTOR_ENABLED=true
RAG_EMBEDDING_ENABLED=true
RAG_VECTOR_BOOTSTRAP=true
DEEPSEEK_WEB_SEARCH_ENABLED=true
```

### 10.4 一键脚本

完整 RAG 模式下，`start.ps1` 的依赖启动策略为：

- Qdrant 已就绪时直接复用；
- Docker 可用时通过 Compose 启动固定版本 Qdrant；
- Docker 不可用时，在 Windows x64 下载官方固定版本、校验脚本内固定 SHA-256，再以隐藏进程启动；
- Embedding 依赖已存在时复用当前 Python，否则创建 `screening-service/.venv-embedding`；
- 启动 `python -m app.embedding_main`，默认最多等待 60 分钟完成 BGE-M3 下载和加载，并在子进程提前退出时立即失败；
- 任一服务未通过 readiness 时停止启动，避免 Java 悄悄降级后仍被误认为完整 RAG 正常。

运行日志位于 `data/runtime/logs`。如果 8080 已被占用，必须先停止旧后端再运行脚本，否则新环境变量无法进入已运行的 JVM。

## 11. 安全与隐私

- 用户问题会发送给 Embedding 服务；如果 Embedding 是外部服务，需要纳入隐私告知和数据处理评估。
- 只有本地无可信答案时才允许联网，降低健康问题外发次数。
- 急症问题不发送给第三方模型。
- 不向模型发送用户资料、录音、筛查报告、病历或数据库记录。
- 不在日志中输出完整问题、搜索内容或 API Key。
- Qdrant 生产环境必须配置 API Key、TLS 和网络隔离，不能把 6333 暴露到公网。
- 联网结果不自动写回审核知识库；只有经过来源许可、人工医学审核、版本化和失效检查后才能进入正式索引。
- Web Search 可能产生额外调用与 token 费用，应设置次数上限、接口限流和超时。

## 12. 测试状态

本次改造包含：

- Java RAG 服务联网分支测试；
- 联网不可用时拒绝编造测试；
- 原分类、轻量检索、Controller 和整体 Spring Context 回归测试；
- Python Embedding API 结构和输入校验测试。

本地验证结果：

- Maven：50 项测试通过。
- 新增 Embedding API 使用 Flask request context 冒烟测试通过。
- 当前 `mock` Conda 环境缺少 pytest，并存在旧 Flask 与新版 Werkzeug 的 `test_client` 兼容问题，因此没有在该环境执行完整 pytest。应根据 `screening-service/requirements.txt` 重建或更新测试环境后再运行完整 Python 测试。
- 未使用真实 DeepSeek Key、真实联网搜索、真实 BGE-M3 下载或运行中的 Qdrant 做集成测试。

## 13. 后续建议

1. 建立 100～300 条离线评测问题，校准 `0.72` 阈值。
2. 将首次 bootstrap 改为独立索引命令，并保存知识版本、审核时间和 embedding 模型版本。
3. 在 Qdrant 中增加 `reviewStatus=APPROVED`、`knowledgeVersion` 和 `sourceGrade` 过滤。
4. 从单纯 dense vector 升级为 BM25/关键词 + dense vector 的混合检索。
5. 对 Top-K 结果增加中文 reranker，降低同分类错误命中。
6. 为 `/assistant/chat` 增加用户/IP 限流、DeepSeek 熔断和联网搜索预算。
7. 记录匿名化的命中类型、相似度和人工反馈，不记录原始敏感问题。
8. 对联网答案增加定期抽检，禁止未经审核内容自动沉淀为生产知识。
