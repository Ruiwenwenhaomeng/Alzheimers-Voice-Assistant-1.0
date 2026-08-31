# 一键启动说明

在项目根目录双击 `start.cmd`，或在 PowerShell 中运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start.ps1
```

启动完成后访问：

```text
http://localhost:8080/
```

当前前端是由 Spring Boot 后端直接托管的静态页面，不需要再单独启动旧的 `C:\Users\zhangyu2024\alz-frontend`。

## 脚本会自动做什么

`start.ps1` 会按顺序完成：

1. 读取 `.env` 中的数据库、Redis、DeepSeek、Python、存储目录等配置。
2. 提示输入 DeepSeek API Key。输入 Key 后先校验 Key 和模型是否可用，并为本次进程强制启用：
   - `DEEPSEEK_WEB_SEARCH_ENABLED=true`
   - `RAG_VECTOR_ENABLED=true`
   - `RAG_EMBEDDING_ENABLED=true`
   - `RAG_VECTOR_BOOTSTRAP=true`
3. 设置并检查 Java 17，默认优先使用 `D:\Professional software\jdk17`。
4. 创建本地运行目录：
   - `data/audio`
   - `data/pdf`
   - `data/admin_audio`
   - `data/runtime`
5. 如果本机 6379 未监听，尝试启动 `C:\Redis\redis-server.exe`。
6. 启动 Python 语音筛查服务：
   - `http://127.0.0.1:5000/api/diagnosis`
7. 在完整 RAG 模式下启动并等待 Qdrant：
   - 优先复用已监听的本地或远程 Qdrant；
   - Docker 可用时执行 `docker compose up -d qdrant`；
   - Docker 不可用时，在 Windows x64 上下载固定版本并校验 SHA-256 后后台启动原生 Qdrant。
8. 创建或复用独立的 Embedding Python 环境，启动 `BAAI/bge-m3` 服务，并等到模型加载成功后再继续：
   - `http://127.0.0.1:7997/v1/embeddings`
9. 启动 Spring Boot 后端和静态前端：
   - `http://localhost:8080/`
10. 后端可访问后自动打开浏览器。

如果 `8080` 端口已经被占用，脚本会退出并要求先停止旧后端。必须重启 Java 进程，新的 RAG 环境变量才会生效。

首次启动完整 RAG 时，需要下载 Qdrant（约 28 MB）、Python 依赖和 BGE-M3 模型，耗时取决于网络和机器性能。Embedding 默认最多等待 3600 秒，可通过 `RAG_EMBEDDING_STARTUP_TIMEOUT_SECONDS` 调整；子进程提前退出时会立即失败。运行日志位于 `data/runtime/logs`。

模型下载默认使用 `HF_ENDPOINT=https://huggingface.co`。脚本会在启动 Embedding 前探测配置端点；自定义端点不可用而官方端点可用时，会自动回退到官方端点。当前 `hf-mirror.com` 在本机对模型元数据请求不兼容，不建议继续配置为默认值。

## Python 环境规则

默认使用已经安装好依赖的 Conda 环境：

```text
D:\Professional software\anaconda3\envs\mock\python.exe
```

查找顺序：

1. `.env` 或当前环境变量中的 `PYTHON_EXE`。
2. 默认 mock 环境 `D:\Professional software\anaconda3\envs\mock\python.exe`。
3. 如果 mock 环境不存在，脚本会询问：

```text
Create/configure a project Python environment from screening-service\requirements.txt? [Y/N]
```

输入 `Y`：根据 `screening-service\requirements.txt` 创建或更新 `screening-service\.venv`。

输入 `N`：直接退出，不启动后端。

Embedding 依赖使用 `screening-service/embedding-requirements.txt`。现有 Python 已包含 `flask`、`sentence-transformers` 和 `torch` 时直接复用；否则创建 `screening-service/.venv-embedding`，不会把大模型依赖强行混入音频筛查虚拟环境。

## DeepSeek 与完整 RAG 启动规则

- 提示符中直接按 Enter：不启用 DeepSeek；系统使用本地 RAG 配置。
- 输入有效 Key：Key 只保存在本次 PowerShell/Java 进程内，不写回 `.env`，并自动打开四个完整 RAG 开关。
- Key、网络或配置模型校验失败：脚本退出，不会以“看似联网、实际已降级”的状态启动。
- Qdrant 或 Embedding readiness 超时：脚本退出并提示对应日志，也不会悄悄启动成轻量检索模式。
- Qdrant 和 Embedding 已运行时：直接复用，不重复创建进程。

本地轻量检索的默认最低分是 `RAG_LOCAL_MIN_SCORE=16`。仅分类相同只能得到 10 分，不能再被当作“找到知识”；问题至少还需命中关键词或足够的双字片段，否则返回空结果并在已启用联网搜索时进入联网分支。

## 常用 .env 配置

```dotenv
PYTHON_DIAGNOSIS_URL=http://127.0.0.1:5000/api/diagnosis
PYTHON_EXE=D:\Professional software\anaconda3\envs\mock\python.exe
PYTHON_AUTO_VENV=false
SCREENING_ENGINE_FACTORY=app.ad_diagnose_engine:create_engine

AUDIO_STORAGE_DIR=C:\Users\Administrator\IdeaProjects\alz-backendalz-backend\alzheimers-voice-assistant\data\audio
PDF_STORAGE_DIR=C:\Users\Administrator\IdeaProjects\alz-backendalz-backend\alzheimers-voice-assistant\data\pdf
ADMIN_AUDIO_STORAGE_DIR=C:\Users\Administrator\IdeaProjects\alz-backendalz-backend\alzheimers-voice-assistant\data\admin_audio

WHISPER_MODEL_SIZE=large-v2
WHISPER_DEVICE=cpu
WHISPER_COMPUTE_TYPE=int8
DEEPSEEK_SCREENING_MODEL=deepseek-reasoner
DEEPSEEK_SCREENING_MAX_TOKENS=1500

RAG_LOCAL_MIN_SCORE=16
RAG_VECTOR_SCORE_THRESHOLD=0.72
QDRANT_BASE_URL=http://127.0.0.1:6333
RAG_EMBEDDING_BASE_URL=http://127.0.0.1:7997/v1
RAG_EMBEDDING_MODEL=BAAI/bge-m3
RAG_EMBEDDING_STARTUP_TIMEOUT_SECONDS=3600
```

## 已迁移到当前前端的功能

当前 `http://localhost:8080/` 页面已包含旧前端中的主要接口功能：

- 登录、注册、退出。
- 科普助手。
- 看图说话任务，包含 `test.jpg`、`test1.jpg`、`test2.jpg`。
- 浏览器录音、上传语音、历史音频播放/下载/删除。
- 个人资料和修改密码。
- 用户风险检测、诊断报告、PDF 列表/查看/下载/删除。
- 管理员用户管理、音频管理、管理员样本上传、管理员检测、统计信息。

## 关于 `/detect`

迁移来的“用户检测”和“管理员检测”按钮会调用后端：

```text
POST /user/detect
POST /admin/detect
```

这两个后端接口依赖外部服务暴露：

```text
POST /detect
```

本仓库当前只包含 `5000/api/diagnosis` 的 Python 语音筛查服务，没有包含 `8000/detect` 服务实现。因此一键启动脚本会提示 `localhost:8000` 是否未监听；如果需要使用这两个检测按钮，请另外启动对应的外部检测服务。
