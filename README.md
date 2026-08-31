# 阿尔茨海默病语音筛查与科普助手

这是一个本地运行的阿尔茨海默病语音风险筛查与健康科普项目，包含：

- Spring Boot 后端。
- 后端托管的静态 Web 前端。
- Python 语音筛查服务 `http://127.0.0.1:5000/api/diagnosis`。
- 登录、注册、历史音频、诊断报告、PDF、管理员管理等接口。
- 从旧前端 `C:\Users\zhangyu2024\alz-frontend` 迁移来的看图说话和管理功能入口。

本系统只用于健康科普和风险提示，不能诊断、排除或治疗阿尔茨海默病。临床判断必须由正规医疗机构完成。

## 一键启动

推荐在项目根目录双击：

```cmd
start.cmd
```

也可以在 PowerShell 中运行：

```powershell
cd C:\Users\Administrator\IdeaProjects\alz-backendalz-backend\alzheimers-voice-assistant
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start.ps1
```

启动后访问：

```text
http://localhost:8080/
```

更详细的启动说明见 [start.md](start.md)。

## 启动脚本会处理的服务

`start.ps1` 会自动：

- 设置 Java 17。
- 读取 `.env`。
- 创建 `data/audio`、`data/pdf`、`data/admin_audio`。
- 尝试启动本机 Redis。
- 使用 `D:\Professional software\anaconda3\envs\mock\python.exe` 启动 Python 筛查服务。
- 如果找不到 mock 环境，由用户决定是否根据 `screening-service\requirements.txt` 创建 `screening-service\.venv`。
- 输入有效 DeepSeek Key 时自动启用联网搜索、向量检索、Embedding 和向量库初始化。
- 启动并等待 Qdrant 与 BGE-M3 Embedding 服务；Docker 不可用时可在 Windows x64 自动下载经 SHA-256 校验的原生 Qdrant。
- 启动 Spring Boot 后端，在完成 Qdrant 知识索引预热并通过 readiness 后打开浏览器。

## 当前前端功能

当前页面不再依赖旧 Vue 前端目录。`frontend/` 和 `backend/src/main/resources/static/` 已同步包含当前静态前端。

已迁移的主要功能：

- 科普助手。
- 用户可在前端保存并即时切换 DeepSeek、Kimi、智谱 GLM、通义千问模型。
- 看图说话任务和三张任务图。
- 浏览器录音、上传语音、历史音频。
- 个人资料、修改密码。
- 用户风险检测、诊断报告、PDF 管理。
- 管理员用户管理、音频管理、管理员样本上传、管理员检测、统计信息。

## 目录结构

- `backend/`：Spring Boot 后端和真正对外提供的静态前端资源。
- `frontend/`：静态前端源文件副本，便于单独查看和后续前端化。
- `screening-service/`：Python 语音筛查服务。
- `data/`：本地运行数据目录，不应提交真实敏感数据。
- `docs/`：项目说明、接口契约和验证文档。
- `deploy/`：数据库初始化和部署相关文件。
- `start.cmd` / `start.ps1`：根目录一键启动入口。

## 端口

- Web 前端和后端 API：`http://localhost:8080/`
- Python 语音筛查服务：`http://127.0.0.1:5000/api/diagnosis`
- Redis：`localhost:6379`
- MySQL：`localhost:3306`
- Qdrant：`http://127.0.0.1:6333`
- Embedding：`http://127.0.0.1:7997/v1/embeddings`

旧前端迁移来的 `/user/detect` 和 `/admin/detect` 还依赖外部 `POST /detect` 服务。当前仓库没有包含 `8000/detect` 的实现；如果需要使用这两个检测按钮，请另外启动对应服务。

## 常用配置

可以复制 `.env.example` 为 `.env`，再按本机情况修改：

```powershell
Copy-Item .env.example .env
```

常用项：

```dotenv
PYTHON_DIAGNOSIS_URL=http://127.0.0.1:5000/api/diagnosis
PYTHON_EXE=D:\Professional software\anaconda3\envs\mock\python.exe
PYTHON_AUTO_VENV=false
SCREENING_ENGINE_FACTORY=app.ad_diagnose_engine:create_engine

DEEPSEEK_API_KEY=
DEEPSEEK_MODEL=deepseek-v4-flash

AUDIO_STORAGE_DIR=C:\Users\Administrator\IdeaProjects\alz-backendalz-backend\alzheimers-voice-assistant\data\audio
PDF_STORAGE_DIR=C:\Users\Administrator\IdeaProjects\alz-backendalz-backend\alzheimers-voice-assistant\data\pdf
ADMIN_AUDIO_STORAGE_DIR=C:\Users\Administrator\IdeaProjects\alz-backendalz-backend\alzheimers-voice-assistant\data\admin_audio
```

不要把真实 API Key、真实语音、转写文本、筛查结果或健康资料提交到仓库。

前端多模型选择、浏览器本地 Key 保存边界和联网检索兼容性见
[多模型切换使用说明](docs/多模型切换使用说明.md)。
