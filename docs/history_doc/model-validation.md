# 语音筛查模型接入与验证门槛

本项目默认的 `QualityOnlyEngine` 只检查录音质量，并始终返回 `INCONCLUSIVE`。
任何会返回 `LOW`、`ELEVATED` 或 `HIGH` 的引擎，都应先完成独立验证。

## 运行时接入

实现 `screening_service.app.engine.ScreeningEngine` 的 `analyze(audio_path, quality)` 方法，
再提供一个无参数工厂函数：

```python
def create_engine():
    return ValidatedScreeningEngine(...)
```

通过环境变量加载：

```powershell
$env:SCREENING_ENGINE_FACTORY = "my_model.adapter:create_engine"
python -m app.main
```

工厂路径或接口错误会让服务启动失败，不会静默降级为一个看似有效的风险模型。

## 独立验证数据

每名受试者只应出现在训练、调参、独立测试中的一个集合。测试集预测保存为 JSONL：

```json
{"subject_id":"S001","label":1,"score":0.82,"quality_passed":true,"language":"普通话","age_group":"70-79","education_group":"中学","sex":"女","device":"mobile-a"}
```

`label` 必须来自预先定义的临床参考标准，而不是同一语音模型的输出。阈值必须在独立测试前固定，
不能在报告测试集成绩后反复调整。

在运行模型评估前，先准备包含所有集合的受试者级数据清单。每行至少包括
`recording_id`、`subject_id`、`split`、`legal_basis`、`usage_permitted`；`test` 或
`external_test` 记录还必须包含 `label`、`label_source` 及语言、年龄、教育、性别、设备字段：

```json
{"recording_id":"R001","subject_id":"S001","split":"external_test","label":1,"label_source":"specialist-consensus-v1","legal_basis":"EXPLICIT_CONSENT","usage_permitted":true,"language":"普通话","age_group":"70-79","education_group":"中学","sex":"女","device":"mobile-a"}
```

运行门禁：

```powershell
python -m evaluation.validate_dataset --input dataset-manifest.jsonl
```

命令会在录音编号重复、受试者跨集合、测试标签依据缺失、使用授权不明确或公平性审计字段
缺失时返回非零退出码。它只能验证清单的一致性，伦理审批和授权文件本身仍需人工核验。

## 评估命令

```powershell
cd screening_service
$env:PYTHONPATH = "$PWD"
python -m evaluation.evaluate --input predictions.jsonl --threshold 0.5
```

工具会输出：混淆矩阵计数、覆盖率、灵敏度、特异度、阳性/阴性预测值、准确率及其
Wilson 95% 置信区间，以及 Brier 分数、ROC AUC 和语言、年龄、教育、性别、设备分组结果。
上线评审还必须补充样本量依据、校准曲线、ROC AUC 的不确定性、外部验证、失败案例和
数据漂移监测。

## 禁止上线的情形

- 独立测试集存在受试者泄漏；
- 只报告总体准确率，没有灵敏度、特异度和质量失败覆盖率；
- 未评估方言、教育程度、年龄、性别或采集设备偏差；
- 用风险分数冒充患病概率；
- 无法追溯训练数据、代码、阈值和 `model_version`；
- 产品页面未持续显示“风险筛查，不能替代临床诊断”。
