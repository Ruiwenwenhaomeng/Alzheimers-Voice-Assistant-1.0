# Windows 双 GPU 加速说明

## 当前加速范围

- `faster-whisper` 使用 CTranslate2 在本机 NVIDIA GPU 上执行语音转写。
- 两个异步筛查 Worker 从 `WHISPER_DEVICE_INDEX` 开始按序绑定 GPU。基准索引为 `0` 时，Worker 1 使用 GPU 0，Worker 2 使用 GPU 1。
- BGE-M3 Embedding 使用 CUDA PyTorch；默认绑定 `RAG_EMBEDDING_DEVICE_INDEX` 指定的 GPU。
- MFCC、录音质量检查和 jieba 语义统计仍在 CPU 执行。
- DeepSeek 推理位于远端 API，本机 GPU 不参与 DeepSeek 模型计算。

## 推荐配置

项目根目录 `.env`：

```dotenv
SCREENING_WORKER_CONCURRENCY=2

WHISPER_DEVICE=cuda
WHISPER_DEVICE_INDEX=0
WHISPER_COMPUTE_TYPE=float16

RAG_EMBEDDING_DEVICE=cuda
RAG_EMBEDDING_DEVICE_INDEX=0
RAG_EMBEDDING_TORCH_VERSION=2.8.0
RAG_EMBEDDING_TORCH_INDEX_URL=https://download.pytorch.org/whl/cu128

CUDA_DLL_PATHS=screening-service/.venv-embedding/Lib/site-packages/torch/lib
GPU_STARTUP_FAILURE_POLICY=fail
```

`GPU_STARTUP_FAILURE_POLICY` 支持：

- `fail`：CUDA、cuBLAS、cuDNN、GPU 数量或计算类型检查失败时停止启动。
- `cpu`：明确记录失败原因，然后将 Whisper 和 Embedding 回退到 CPU。

生产或固定 GPU 主机建议使用 `fail`，避免系统在无人注意时退回 CPU 并导致任务耗时突然增加。

## 启动检查逻辑

`start.ps1` 在服务启动前执行以下检查：

1. CTranslate2 能否识别目标 GPU。
2. `float16` 是否被当前 GPU 支持。
3. `cublas64_12.dll`、`cudnn64_9.dll` 和 `cudnn_ops64_9.dll` 能否加载。
4. CUDA PyTorch 是否能执行 GPU 矩阵运算和 cuDNN 卷积。
5. Worker 数量是否超过从基准索引开始可用的 GPU 数量。
6. 已运行的 Python API、Embedding 或 Worker 配置是否过期；不一致时只重启能够确认属于本项目的进程。

如果 Embedding 环境还是 CPU PyTorch，启动脚本会从配置的 PyTorch 官方 CUDA 源安装 CUDA 构建。安装前会升级 `pip` 和 `wheel`。

## 启动与验证

在项目根目录运行：

```powershell
.\start.ps1
```

DeepSeek Key 仍只保存在本次启动进程内，需要按提示重新输入。

检查 Worker 绑定：

```powershell
Get-Content .\data\runtime\logs\screening-worker-1.err.log -Tail 30
Get-Content .\data\runtime\logs\screening-worker-2.err.log -Tail 30
```

日志应分别包含：

```text
whisperDevice=cuda whisperDeviceIndex=0 whisperComputeType=float16 workerId=1
whisperDevice=cuda whisperDeviceIndex=1 whisperComputeType=float16 workerId=2
```

检查 Embedding：

```powershell
Invoke-RestMethod http://127.0.0.1:7997/health
nvidia-smi
```

健康接口应返回 `device: cuda:0`，`nvidia-smi` 中应出现 Embedding 和正在处理任务的 Worker Python 进程。

## 手工探针

```powershell
Set-Location .\screening-service
.\.venv\Scripts\python.exe -m app.gpu_runtime whisper --device-index 0 --compute-type float16
.\.venv\Scripts\python.exe -m app.gpu_runtime whisper --device-index 1 --compute-type float16
.\.venv-embedding\Scripts\python.exe -m app.gpu_runtime embedding --device-index 0
```

三个命令都应返回 JSON，且 `status` 为 `UP`。
