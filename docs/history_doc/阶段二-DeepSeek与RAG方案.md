# 阶段二：DeepSeek 与 RAG 健康咨询助手

## 目标

在保留阶段一规则助手安全降级能力的基础上，增加：

1. DeepSeek 对话 API；
2. 三个知识域的问题分类 Skill；
3. 30 个经过整理的常见问题及标准答案；
4. 基于本地知识条目的 RAG 检索；
5. 急症优先、知识不足拒答、来源展示和 API 失败降级。

## 请求链路

```text
用户问题
  -> 本地 ad-question-router（不调用模型）
      -> emergency：直接返回急救提示
      -> out_of_scope：返回范围边界
      -> introduction / symptoms / coping
          -> 从 ad-faq.json 检索 Top-K 知识
              -> DeepSeek 已启用：知识片段 + 问题 -> 生成回答
              -> DeepSeek 未启用或失败：返回命中度最高的标准答案
```

问题分类使用本地关键词和安全规则，因此不需要为了“决定检索哪个知识域”额外调用一次大模型。这样减少了一次网络往返，也避免急症问题进入普通生成链路。

## 三个知识域

| 知识域 | 数量 | 范围 |
|---|---:|---|
| `INTRODUCTION` | 100 | 疾病定义、痴呆区别、脑部变化、病因、风险、遗传、分期、轻度认知障碍等 |
| `SYMPTOMS` | 100 | 记忆、语言、定向、视觉空间、执行功能、行为情绪、生活能力和就医信号等 |
| `COPING` | 100 | 就医准备、检查、治疗、风险管理、沟通、居家安全、进食和照护者支持等 |

知识文件：`backend/src/main/resources/knowledge/ad-faq.json`。可重复生成脚本：`scripts/generate-ad-faq.ps1`。

## 来源分层

- A 级：国家卫生健康委员会、世界卫生组织等政府或国际公共卫生机构；
- B 级：美国国家老龄研究所等政府研究机构，以及标明作者或审核机构的科普中国内容；
- C 级：维基百科等开放编辑资料，仅用于概念补充，不应独立支撑诊断、治疗、药物和风险数字。

每条知识保存来源标题和原始链接。正式上线前应增加 `reviewedAt`、审核医生、内容版本、失效链接检查和定期复审流程。网页内容不得直接整页复制，应保存经审核的摘要和必要元数据。

当前主要种子来源：

- 国家卫生健康委《阿尔茨海默病预防与干预核心信息》：<https://www.nhc.gov.cn/lljks/c100158/201909/c124c2c91fb74701b11d560aba0ad827.shtml>
- 世界卫生组织 Dementia fact sheet：<https://www.who.int/news-room/fact-sheets/detail/dementia>
- 美国国家老龄研究所常见表现与诊断资料：<https://www.nia.nih.gov/health/alzheimers-symptoms-and-diagnosis/what-are-signs-alzheimers-disease>
- 科普中国认知症沟通内容：<https://www.kepuchina.cn/article/articleinfo?ar_id=491252&business_type=100>
- 维基百科阿尔茨海默病条目（补充来源）：<https://zh.wikipedia.org/wiki/阿茲海默症>

## DeepSeek 配置

截至 2026-07-16，DeepSeek 官方文档推荐 `deepseek-v4-flash` 和 `deepseek-v4-pro`；旧模型名 `deepseek-chat`、`deepseek-reasoner` 将于 2026-07-24 弃用。项目默认使用 `deepseek-v4-flash`，并允许通过环境变量覆盖。

```dotenv
DEEPSEEK_ENABLED=true
DEEPSEEK_API_KEY=从部署环境安全注入，不要提交到代码仓库
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_MODEL=deepseek-v4-flash
DEEPSEEK_MAX_TOKENS=700
DEEPSEEK_CONNECT_TIMEOUT_MS=3000
DEEPSEEK_READ_TIMEOUT_MS=20000
RAG_TOP_K=4
```

接口使用 `POST /chat/completions`，非流式响应，关闭思考模式以降低普通科普问答的等待时间。官方接口说明：<https://api-docs.deepseek.com/zh-cn/api/create-chat-completion/>。

## 安全与隐私

- API Key 只从环境变量读取；
- 默认 `DEEPSEEK_ENABLED=false`，本地知识问答仍可工作；
- 不向模型发送数据库用户资料、录音、筛查报告或完整病历；
- 当前只发送用户本轮问题和检索到的知识摘要；
- 急症问题不调用模型；
- 系统提示要求仅依据知识片段回答、资料不足时说明边界；
- API 超时、限流或异常时自动返回本地标准答案；
- 所有回答继续展示医疗免责声明和来源链接。

在生产环境将用户健康问题发送给第三方模型前，还需要完成用户告知与同意、数据处理协议、日志脱敏、保存期限和跨境/区域合规评估。

## 当前 RAG 能力边界

当前版本是可运行的轻量 RAG：先分类，再根据关键词、问题相似双字片段和知识域进行 Top-K 检索。知识库包含300条审核问答；随着内容继续增长，应优先使用向量检索方案。

该能力已在阶段三增加可选的 Qdrant 向量检索、独立 Embedding 服务和 DeepSeek Anthropic Web Search。启用方式、阈值、降级与安全边界见 [阶段三-向量RAG与联网搜索方案.md](阶段三-向量RAG与联网搜索方案.md)。未启用阶段三配置时仍保持本节所述轻量 RAG 行为。

知识扩展到数百或数千条后，建议增加：

1. 网页采集暂存区和来源许可检查；
2. 医学审核、去重、切块和版本管理；
3. 中文向量模型及向量数据库；
4. 混合检索（关键词/BM25 + 向量）和重排序；
5. 离线问答评测集、引用正确率和危险回答测试；
6. 只有通过审核的内容才能进入生产索引。

## Skill

项目内 Skill 位于 `skills/ad-question-router/`，使用标准 `SKILL.md` 和 `agents/openai.yaml`。Java 运行时对应实现为 `KeywordAdQuestionRouter`，两者使用相同的分类集合：

- `introduction`
- `symptoms`
- `coping`
- `emergency`
- `out_of_scope`

Skill 只做分类，不生成医疗回答，也不作诊断。
