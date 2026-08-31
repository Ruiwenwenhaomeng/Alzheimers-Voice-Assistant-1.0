# Python 语音筛查服务契约（v1）

Java 后端通过 `PYTHON_DIAGNOSIS_URL` 调用筛查服务。该服务输出的是认知风险提示，
不是阿尔茨海默病诊断。

## 请求

```http
POST /api/diagnosis
Content-Type: application/json

{
  "audio_path": "C:/shared/audio/example.wav"
}
```

`audio_path` 必须是 Java 和 Python 服务都能访问的共享文件路径。如果两个服务运行在不同
容器或主机，应把音频目录挂载到双方可见的同一路径，或在后续契约版本中改用文件上传。

## 成功响应

```json
{
  "transcription": "受试者的语音转写文本",
  "report": "面向用户的风险解释，不使用确诊措辞",
  "risk_level": "ELEVATED",
  "risk_score": 0.68,
  "quality_passed": true,
  "quality_issues": [],
  "feature_highlights": [
    "与模型参考分布相比，停顿比例偏高",
    "叙事信息单元较少"
  ],
  "model_version": "speech-screening-2026.07"
}
```

字段约束：

| 字段 | 必填 | 约束 |
| --- | --- | --- |
| `transcription` | 是 | 非空字符串 |
| `report` | 是 | 非空字符串，只能解释风险，不得宣称确诊 |
| `risk_level` | 建议 | `LOW`、`ELEVATED`、`HIGH`、`INCONCLUSIVE` |
| `risk_score` | 建议 | 0 到 1；是模型风险指标，不应表述为患病概率 |
| `quality_passed` | 建议 | 录音和任务质量是否足以解释风险 |
| `quality_issues` | 建议 | 如噪声过高、时长不足、非目标说话人等 |
| `feature_highlights` | 建议 | 只描述可解释的观测，不直接推导疾病结论 |
| `model_version` | 建议 | 可追溯的模型/规则版本 |

如果 `risk_level` 缺失或无法识别，Java 后端会使用 `INCONCLUSIVE`。只有
`quality_passed=true`、风险等级明确且 `model_version` 非空时，结果才标为 `COMPLETED`；
否则一律为 `REVIEW_REQUIRED`，也不会从自由文本报告中猜测风险等级。

## 质量与模型要求

- 在 `quality_passed=false` 时，不应向用户展示确定的风险等级，应提示重新采集或人工复核。
- 阈值必须在独立验证集上预先确定，并按语言、方言、年龄、教育程度、性别和设备评估偏差。
- 每次模型升级应更新 `model_version`，保存性能、校准、数据来源和适用人群记录。
- 临床验证完成前，产品界面必须持续显示“风险筛查，不能替代临床诊断”。

## 错误响应

服务应使用标准 HTTP 状态码：

- `400`：路径或音频格式错误；
- `422`：音频可读取但质量不足以处理；
- `500`：模型内部错误；
- `503`：模型暂时不可用。

Java 后端会把非 2xx、连接失败、超时和响应字段缺失转换为统一的筛查服务异常。
