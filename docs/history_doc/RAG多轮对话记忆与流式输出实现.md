# RAG 多轮对话记忆与流式输出实现

## 目标与结果

知识助手现在支持多个独立对话、每个对话最多 100 个用户轮次、完整历史持久化、每 10 轮摘要一次，以及 SSE 流式回答。旧的 `POST /assistant/chat` 保留为无状态兼容接口。

这里没有把 100 轮原文全部发送给模型。模型每次只接收：

1. 一份有长度上限的滚动摘要；
2. 摘要之后最多 9 个已完成轮次；
3. 当前问题；
4. 本轮 RAG 检索到的知识片段。

因此上下文大小是有界的，不会随对话从第 1 轮增长到第 100 轮。MySQL 是事实来源，Redis 只缓存当前可用上下文；Redis 故障时自动从 MySQL 重建。

## 数据模型

- `assistant_conversation`：会话所有者、标题、轮次数、已摘要到的轮次、滚动摘要和生成锁。
- `assistant_message`：用户和助手的完整原始消息，每个会话最多 200 条。
- `assistant_memory`：每 10 轮形成的分段摘要，便于审计和后续重新生成滚动摘要。

已存在数据库需要执行：

```sql
SOURCE deploy/mysql/migrations/V004__assistant_conversation_memory.sql;
```

全新环境使用 `deploy/mysql/init.sql` 时会直接创建这些表。

## 会话归属

- 登录用户：使用 JWT 中的用户名隔离会话。
- 匿名用户：浏览器生成 UUID，并通过 `X-Assistant-Client-Id` 发送；UUID 存在 `localStorage`。
- 服务端每次读取、写入和删除都同时校验会话 ID 与所有者，不能通过猜测 UUID 读取其他人的对话。

匿名会话和登录会话不会自动合并。若后续需要匿名转登录迁移，应增加一次性、需用户确认的归属迁移接口，而不是在登录时静默合并。

## API

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/assistant/conversations` | 新建对话 |
| `GET` | `/assistant/conversations` | 查询当前所有者的对话列表 |
| `GET` | `/assistant/conversations/{id}` | 查询会话及全部历史消息 |
| `DELETE` | `/assistant/conversations/{id}` | 删除会话和关联消息、摘要 |
| `POST` | `/assistant/conversations/{id}/messages/stream` | 发送问题并接收 SSE 流 |

流式事件依次为：

- `start`：会话 ID、当前轮次和轮次上限；
- `delta`：回答正文增量；
- `complete`：完整回答元数据和最新会话状态；
- `error`：生成或连接错误。

反向代理应关闭该路径的响应缓冲。接口已返回 `X-Accel-Buffering: no` 和 `Cache-Control: no-cache, no-transform`；Nginx 仍建议对该 location 配置 `proxy_buffering off`。

## 摘要策略

第 10、20、30……100 轮完成后：

1. 读取刚完成的 10 轮原文；
2. 保存一份本地可重建的分段摘要；
3. 请求模型将旧滚动摘要与新 10 轮合并；
4. 模型不可用时使用确定性的本地压缩降级；
5. 将滚动摘要限制在 `RAG_MEMORY_SUMMARY_MAX_CHARS` 以内；
6. 刷新 Redis 中的有界上下文。

默认参数：

```dotenv
RAG_MEMORY_MAX_TURNS=100
RAG_MEMORY_SUMMARY_INTERVAL=10
RAG_MEMORY_SUMMARY_MAX_CHARS=1800
RAG_MEMORY_CACHE_TTL_MINUTES=1440
RAG_MEMORY_GENERATION_TIMEOUT_SECONDS=180
RAG_MEMORY_SUMMARY_CONCURRENCY=2
RAG_MEMORY_SUMMARY_QUEUE_CAPACITY=100
```

同一会话同一时间只允许生成一个回答。生成锁超过超时时间后可以被新请求回收，防止服务异常退出造成永久锁死。
回答消息提交后会立即结束 SSE；摘要由独立的有界线程池生成，不占用流式请求线程或数据库事务。若摘要队列短暂满载，后续完成的轮次会继续补做尚未生成的 10 轮摘要块。

## 不输出思考过程

DeepSeek 请求包含 `thinking.type=disabled`。流式解析只读取 `choices[].delta.content`，明确忽略 `reasoning_content`；同时使用跨分片状态过滤器删除 `<think>...</think>`。过滤后的正文才会发送给浏览器并持久化。

这属于输出防护，不应把它当成提示词安全边界。模型系统提示、RAG 知识和用户对话记忆仍分别标注，历史对话只用于理解指代，不能覆盖医学安全规则或作为医学事实来源。

## 上线建议

- 给会话创建、发送和列表接口增加用户级/客户端级限流。
- 将 SSE 的 MVC 执行器改为有界线程池，并监控活跃流、首字延迟、完整生成延迟和断连率。
- 为摘要任务增加失败指标；更高负载时可将本机有界线程池升级为 RabbitMQ 任务，并保留幂等的摘要区间键。
- 对消息和摘要设置保留期限及用户主动清除入口，避免长期保存敏感健康信息。
- 若实例数量增加，当前 MySQL 原子生成锁和 Redis 缓存可跨实例工作；反向代理无需粘性会话。
