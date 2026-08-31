# 阿尔茨海默病语音筛查与科普助手

这是一个 Spring Boot 后端原型，提供用户与档案管理、语音上传、外部 Python
筛查服务对接、历史报告，以及可审计的阿尔茨海默病科普助手。

应用启动后访问 `http://localhost:8080/`，即可使用适合大字号和移动端的科普对话页面；
登录后可以直接在浏览器录制 PCM WAV、上传、查看历史录音并触发结构化风险筛查。

> 本系统只能提供风险筛查和健康科普，不能诊断、排除或治疗阿尔茨海默病。
> 临床判断必须由正规医疗机构完成。

## 智能助手接口

科普接口不要求登录，回答包含意图、行动建议、医疗免责声明和权威来源。

```http
POST /assistant/chat
Content-Type: application/json

{"message":"语音筛查可以确诊吗？"}
```

支持的主题：常见表现、语音筛查、就医评估、风险管理、治疗常识和家庭照护。
当问题包含突然语言障碍、口角歪斜、单侧无力或意识异常等急症信号时，接口会返回
`urgent: true` 并提示立即拨打 120。

```http
GET /assistant/topics
GET /assistant/screening-guide
```

`screening-guide` 提供录音前知情同意、建议采集任务、结果影响因素、停止条件和隐私提示。
其中的语音任务是产品采集指引，不是已完成临床验证的诊断量表。

原有 `GET /audio/diagnosis/{audioId}` 接口会在 Python 输出之外返回
`screeningType: "RISK_SCREENING"`、`medicalDisclaimer` 和 `recommendedActions`。
接口会先校验该音频确实属于当前登录用户。

语音上传使用 `multipart/form-data`：

```text
POST /audio/upload
file=<PCM WAV>
consentAccepted=true
consentVersion=voice-screening-consent-v1
taskType=NATURAL_SPEECH
```

后端强制校验当前同意版本，并把同意时间、版本和任务类型写入音频记录。它只接受 25 MB
以内、1 秒至 5 分钟、8–48 kHz 的单/双声道 PCM WAV；数据库时长来自服务器解析，
不会信任客户端提交的 `duration`。每次筛查还会保存独立 `screeningId`、质量问题、风险值、
特征提示、模型版本和免责声明版本，供历史复核与审计。

## 本地配置

生产凭据不要提交到仓库。启动前通过环境变量提供：

| 环境变量 | 说明 | 默认值 |
| --- | --- | --- |
| `DB_URL` | MySQL JDBC 地址 | 本地 `alz_system` 数据库 |
| `DB_USERNAME` | 数据库用户名 | `alz` |
| `DB_PASSWORD` | 数据库密码 | `alz-dev-password` |
| `REDIS_HOST` | Redis 地址 | `localhost` |
| `REDIS_PORT` | Redis 端口 | `6379` |
| `JWT_SECRET` | JWT HMAC 密钥，生产环境至少 32 字节 | 仅本地开发使用内置值 |
| `CORS_ALLOWED_ORIGINS` | 允许访问 API 的前端来源，逗号分隔 | `http://localhost:3000,http://localhost:5173` |
| `PYTHON_DIAGNOSIS_URL` | Python 语音筛查接口 | `http://localhost:5000/api/diagnosis` |
| `PYTHON_CONNECT_TIMEOUT_MS` | Python 服务连接超时 | `3000` |
| `PYTHON_READ_TIMEOUT_MS` | Python 服务处理超时 | `120000` |
| `AUDIO_STORAGE_DIR` | 用户语音存储目录 | `data/audio` |
| `PDF_STORAGE_DIR` | PDF 报告存储目录 | `data/pdf` |
| `ADMIN_AUDIO_STORAGE_DIR` | 管理员样本目录 | `data/admin_audio` |

仓库提供了 MySQL 8.4、Redis 7.4 和完整建表脚本。首次本地运行可执行：

```powershell
docker compose up -d mysql redis
docker compose ps
```

默认开发数据库账号为 `alz / alz-dev-password`。生产环境必须覆盖这些值。
MySQL 初始化脚本位于 `deploy/mysql/init.sql`；复制 `.env.example` 为 `.env` 后可按需修改配置。
如果已有旧数据库卷，需要按顺序执行
`deploy/mysql/migrations/V002__screening_audit.sql`；全新数据库已经包含这些字段，不要重复执行迁移。

语音分析依赖 Python 服务。Java 后端会发送 `{"audio_path":"..."}`，服务必须返回非空的
`transcription` 和 `report` 字段。连接失败、处理超时、非 2xx 状态及字段缺失会转换为明确的筛查服务错误。
结构化风险等级、质量状态、模型版本等字段见
[Python 语音筛查服务契约](docs/python-screening-contract.md)。旧服务仅返回文本时，后端会把结果标记为
`INCONCLUSIVE`，不会猜测风险等级。

使用 JDK 25 运行：

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-25"
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

仓库内还提供了安全的 Python 参考服务。它会检查 WAV 时长、采样率、音量、静音比例和削波，
但没有内置未经验证的认知分类规则，因此默认只返回 `INCONCLUSIVE`：

```powershell
cd screening_service
python -m pip install -r requirements.txt
$env:AUDIO_ROOT = "C:\path\to\alz-backendalz-backend\data\audio"
python -m app.main
```

运行 Python 测试：

```powershell
$env:PYTHONPATH = "$PWD"
python -m pytest tests -q
```

要产生 `LOW`、`ELEVATED` 或 `HIGH` 风险等级，必须接入经过独立验证的转写和分类引擎，
通过 `SCREENING_ENGINE_FACTORY=module:function` 配置工厂，并遵守
[Python 语音筛查服务契约](docs/python-screening-contract.md) 和
[模型验证门槛](docs/model-validation.md) 的质量、偏差和版本要求。仓库中的评估工具可计算覆盖率、
灵敏度、特异度、预测值、Brier 分数、ROC AUC 和分组表现。

## 安全与隐私

- 语音、转写文本和报告属于敏感健康信息，应取得知情同意并实行最小化采集。
- 上传接口会拒绝未明确同意或仍使用旧同意文本版本的请求，并持久化同意证据。
- 用户可调用 `DELETE /audio/{audioId}` 撤回并删除本人原始语音、结构化筛查结果和关联 PDF；
  网页历史列表已提供同一操作。
- 结构化筛查结果只有在音频质量通过、风险等级明确且模型版本存在时才标记为 `COMPLETED`；
  旧接口或信息不全的返回一律标记为 `REVIEW_REQUIRED`。
- 音频筛查、报告查看和下载必须校验登录用户及资源归属。
- 用户音频通过 `GET /audio/file/{name}` 读取；管理员样本通过
  `GET /admin/audios/admin/file/{filename}` 读取。旧的敏感目录静态映射已移除。
- 新密码使用 BCrypt；历史明文密码会在用户首次成功登录时自动迁移。
- 管理员与普通用户角色由 MVC 拦截器在 Controller 方法匹配后校验。
- 数据库凭据已改为环境变量；曾提交或共享过的真实凭据仍应立即轮换。
- 上线时必须设置独立的 `JWT_SECRET`，并完成访问审计、数据删除策略和传输/存储加密。

## 科普依据

- [国家卫生健康委：阿尔茨海默病预防与干预核心信息](https://www.nhc.gov.cn/lljks/c100158/201909/c124c2c91fb74701b11d560aba0ad827.shtml)
- [国家卫生健康委：老年痴呆防治促进行动](https://www.nhc.gov.cn/lljks/c100158/202306/6a4c7f7a6f0c47cbb609a07e7f78582d.shtml)
- [美国国家老龄研究所：痴呆的症状、类型与诊断](https://www.nia.nih.gov/health/alzheimers-and-dementia/what-dementia-symptoms-types-and-diagnosis)
- [世界卫生组织：Dementia fact sheet](https://www.who.int/news-room/fact-sheets/detail/dementia)
