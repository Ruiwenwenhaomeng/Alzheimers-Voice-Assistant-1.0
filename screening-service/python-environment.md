# Python 语音筛查环境说明

根目录一键启动脚本会启动本目录下的 Python 语音筛查服务：

```text
http://127.0.0.1:5000/api/diagnosis
```

该服务用于接收后端传入的音频路径，完成音频质量检测、转写、特征提取和报告生成。

## 默认 Python 环境

本机优先使用已经安装好依赖的 Conda 环境：

```text
D:\Professional software\anaconda3\envs\mock\python.exe
```

这个 `mock` 环境已包含当前筛查脚本需要的依赖，因此正常情况下不需要重新安装 Python 包。

## 启动脚本查找顺序

`start.ps1` 启动 Python 服务时按以下顺序选择解释器：

1. 如果 `.env` 或当前环境变量配置了 `PYTHON_EXE`，且路径存在，优先使用它。
2. 否则使用默认 mock 环境：
   `D:\Professional software\anaconda3\envs\mock\python.exe`
3. 如果 mock 环境不存在，脚本询问：

```text
Create/configure a project Python environment from screening-service\requirements.txt? [Y/N]
```

输入 `Y`：创建或更新：

```text
screening-service\.venv
```

并根据：

```text
screening-service\requirements.txt
```

安装依赖。

输入 `N`：直接退出启动流程，不继续启动后端。

## 依赖安装缓存

当使用 fallback `.venv` 时，脚本会在：

```text
screening-service\.venv\.requirements.sha256
```

记录 `requirements.txt` 的哈希值。只有当 `requirements.txt` 变化时，才会重新执行：

```powershell
python -m pip install -r requirements.txt
```

## 常用 .env 配置

```dotenv
PYTHON_EXE=D:\Professional software\anaconda3\envs\mock\python.exe
PYTHON_AUTO_VENV=false
PYTHON_DIAGNOSIS_URL=http://127.0.0.1:5000/api/diagnosis
SCREENING_ENGINE_FACTORY=app.ad_diagnose_engine:create_engine
```

只有在确实希望启动时强制创建或更新 `screening-service\.venv` 时，才把：

```dotenv
PYTHON_AUTO_VENV=true
```

## 手动启动

通常不需要手动启动 Python 服务；根目录 `start.cmd` / `start.ps1` 会自动处理。

排查问题时可以手动运行：

```powershell
cd C:\Users\Administrator\IdeaProjects\alz-backendalz-backend\alzheimers-voice-assistant\screening-service
$env:AUDIO_ROOT="C:\Users\Administrator\IdeaProjects\alz-backendalz-backend\alzheimers-voice-assistant\data\audio"
& "D:\Professional software\anaconda3\envs\mock\python.exe" -m app.main
```
