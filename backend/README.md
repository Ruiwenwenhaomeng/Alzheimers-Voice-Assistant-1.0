# 后端说明

本目录是 Spring Boot 后端，负责：

- 用户登录、注册、JWT 鉴权。
- 科普助手接口。
- 音频上传、播放、下载、删除。
- Python 语音筛查服务对接。
- 诊断报告和 PDF 管理。
- 管理员用户、音频、样本和统计接口。
- 托管 `src/main/resources/static/` 下的当前静态前端。

启动后访问：

```text
http://localhost:8080/
```

## 推荐启动方式

不要优先在本目录单独启动。推荐回到项目根目录运行：

```powershell
cd C:\Users\Administrator\IdeaProjects\alz-backendalz-backend\alzheimers-voice-assistant
.\start.cmd
```

或：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start.ps1
```

根目录脚本会同时准备 Java、Redis、Python 筛查服务、运行目录和浏览器打开逻辑。

## 手动启动后端

排查问题时可手动启动：

```powershell
$env:JAVA_HOME="D:\Professional software\jdk17"
$env:Path="$env:JAVA_HOME\bin;$env:Path"

$env:AUDIO_STORAGE_DIR="C:\Users\Administrator\IdeaProjects\alz-backendalz-backend\alzheimers-voice-assistant\data\audio"
$env:PDF_STORAGE_DIR="C:\Users\Administrator\IdeaProjects\alz-backendalz-backend\alzheimers-voice-assistant\data\pdf"
$env:ADMIN_AUDIO_STORAGE_DIR="C:\Users\Administrator\IdeaProjects\alz-backendalz-backend\alzheimers-voice-assistant\data\admin_audio"
$env:PYTHON_DIAGNOSIS_URL="http://127.0.0.1:5000/api/diagnosis"

cd C:\Users\Administrator\IdeaProjects\alz-backendalz-backend\alzheimers-voice-assistant\backend
& "D:\Professional software\IntelliJ IDEA 2024.3.2\plugins\maven\lib\maven3\bin\mvn.cmd" spring-boot:run
```

## 关键配置

配置项来自 `src/main/resources/application.yml`，可通过环境变量或根目录 `.env` 覆盖：

| 变量 | 用途 |
| --- | --- |
| `DB_URL` | MySQL JDBC 地址 |
| `DB_USERNAME` | MySQL 用户名 |
| `DB_PASSWORD` | MySQL 密码 |
| `REDIS_HOST` | Redis 地址 |
| `REDIS_PORT` | Redis 端口 |
| `JWT_SECRET` | JWT 签名密钥 |
| `AUDIO_STORAGE_DIR` | 用户音频目录 |
| `PDF_STORAGE_DIR` | PDF 报告目录 |
| `ADMIN_AUDIO_STORAGE_DIR` | 管理员样本音频目录 |
| `PYTHON_DIAGNOSIS_URL` | Python 语音筛查服务地址 |
| `DEEPSEEK_API_KEY` | DeepSeek API Key |
| `DEEPSEEK_ENABLED` | 是否启用 DeepSeek |

## Python 筛查服务

后端默认调用：

```text
http://127.0.0.1:5000/api/diagnosis
```

根目录 `start.ps1` 会使用：

```text
D:\Professional software\anaconda3\envs\mock\python.exe
```

启动 `screening-service`。如果找不到该环境，会由用户决定是否根据 `screening-service\requirements.txt` 创建 `.venv`。

## 旧前端迁移接口

当前静态前端已经迁入旧前端中的主要按钮接口，包括：

- `/assistant/chat`
- `/audio/upload`
- `/audio/my`
- `/audio/file/{name}`
- `/audio/diagnosis/check/{name}`
- `/audio/diagnosis/{name}`
- `/audio/pdf/*`
- `/user/profile/*`
- `/user/change-password`
- `/user/detect`
- `/admin/users*`
- `/admin/audios*`
- `/admin/detect`
- `/admin/stats`

其中 `/user/detect` 和 `/admin/detect` 依赖外部 `POST /detect` 服务。当前仓库没有包含 `8000/detect` 实现；需要使用这两个接口时请另外启动对应服务。

## 测试

```powershell
$env:JAVA_HOME="D:\Professional software\jdk17"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
& "D:\Professional software\IntelliJ IDEA 2024.3.2\plugins\maven\lib\maven3\bin\mvn.cmd" test
```

静态前端资源测试：

```powershell
& "D:\Professional software\IntelliJ IDEA 2024.3.2\plugins\maven\lib\maven3\bin\mvn.cmd" -Dtest=StaticAppTest test
```
