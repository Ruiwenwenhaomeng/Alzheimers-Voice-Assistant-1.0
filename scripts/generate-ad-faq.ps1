param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'

$knowledgePath = Join-Path $RepositoryRoot 'backend\src\main\resources\knowledge\ad-faq.json'
$existing = Get-Content -LiteralPath $knowledgePath -Raw -Encoding UTF8 | ConvertFrom-Json
$baseDocuments = @($existing | Where-Object { $_.id -match '^K(?:0[1-9]|[12][0-9]|30)$' })

if ($baseDocuments.Count -ne 30) {
    throw "Expected the 30 original K01-K30 documents, found $($baseDocuments.Count)."
}

$sourceCatalog = @{
    nhcCore = [ordered]@{ title = '国家卫生健康委：阿尔茨海默病预防与干预核心信息'; url = 'https://www.nhc.gov.cn/lljks/c100158/201909/c124c2c91fb74701b11d560aba0ad827.shtml' }
    nhcAction = [ordered]@{ title = '国家卫生健康委：老年痴呆防治促进行动（2023—2025年）'; url = 'https://app.www.gov.cn/govdata/gov/202306/15/504197/article.html' }
    who = [ordered]@{ title = '世界卫生组织：Dementia fact sheet'; url = 'https://www.who.int/news-room/fact-sheets/detail/dementia' }
    niaFact = [ordered]@{ title = '美国国家老龄研究所：Alzheimer Disease Fact Sheet'; url = 'https://www.nia.nih.gov/health/alzheimers-disease-fact-sheet' }
    niaCauses = [ordered]@{ title = '美国国家老龄研究所：What Causes Alzheimer Disease?'; url = 'https://www.nia.nih.gov/health/alzheimers-causes-and-risk-factors/what-causes-alzheimers-disease' }
    niaGenetics = [ordered]@{ title = '美国国家老龄研究所：Alzheimer Disease Genetics Fact Sheet'; url = 'https://www.nia.nih.gov/health/alzheimers-disease-genetics-fact-sheet' }
    niaDiagnosis = [ordered]@{ title = '美国国家老龄研究所：Alzheimer symptoms and diagnosis'; url = 'https://www.nia.nih.gov/health/alzheimers-symptoms-and-diagnosis' }
    niaBiomarkers = [ordered]@{ title = '美国国家老龄研究所：How Biomarkers Help Diagnose Dementia'; url = 'https://www.nia.nih.gov/health/alzheimers-symptoms-and-diagnosis/how-biomarkers-help-diagnose-dementia' }
    niaTreatment = [ordered]@{ title = '美国国家老龄研究所：How Is Alzheimer Disease Treated?'; url = 'https://www.nia.nih.gov/health/how-alzheimers-disease-treated' }
    niaCaregiving = [ordered]@{ title = '美国国家老龄研究所：Alzheimer caregiving'; url = 'https://www.nia.nih.gov/health/alzheimers-caregiving' }
    niaBehavior = [ordered]@{ title = '美国国家老龄研究所：Alzheimer changes in behavior and communication'; url = 'https://www.nia.nih.gov/health/alzheimers-changes-behavior-and-communication' }
    niaSafety = [ordered]@{ title = '美国国家老龄研究所：Alzheimer Caregiving—Home Safety Tips'; url = 'https://www.nia.nih.gov/health/safety/alzheimers-caregiving-home-safety-tips' }
    niaLate = [ordered]@{ title = '美国国家老龄研究所：Care in the Last Stages of Alzheimer Disease'; url = 'https://www.nia.nih.gov/health/alzheimers-caregiving/care-last-stages-alzheimers-disease' }
    nice = [ordered]@{ title = 'NICE：Dementia—assessment, management and support'; url = 'https://www.nice.org.uk/guidance/ng97' }
}

$clusters = @'
[
  {
    "category": "INTRODUCTION",
    "questions": ["阿尔茨海默病会让大脑发生哪些变化？", "淀粉样蛋白斑块与阿尔茨海默病有什么关系？", "tau蛋白缠结是什么意思？", "阿尔茨海默病为什么会损伤神经元？", "疾病症状出现前大脑就可能改变吗？"],
    "answer": "阿尔茨海默病涉及多种复杂脑部变化，常见研究标志包括β-淀粉样蛋白斑块、异常tau蛋白缠结、神经元连接受损和脑萎缩。这些变化可能在明显症状前多年开始，但发现某一种变化不能脱离完整临床评估单独下诊断。",
    "keywords": ["大脑变化", "淀粉样蛋白", "tau蛋白", "神经元", "脑萎缩", "病理"],
    "actionSuggestions": ["把生物标志物结果交由专科医生综合解释", "不要仅凭单项检查自行确诊"],
    "sourceKeys": ["niaFact", "niaBiomarkers"]
  },
  {
    "category": "INTRODUCTION",
    "questions": ["阿尔茨海默病在痴呆中常见吗？", "痴呆最常见的病因是哪一种？", "所有痴呆患者都是阿尔茨海默病吗？", "阿尔茨海默病大约占痴呆的多少？", "为什么谈痴呆时经常提到阿尔茨海默病？"],
    "answer": "阿尔茨海默病是最常见的痴呆病因，世界卫生组织估计可占痴呆病例的60%至70%，但痴呆还可能由血管性病变、路易体病、额颞叶变性等造成，也可能有多种病因并存。",
    "keywords": ["最常见", "痴呆病因", "比例", "60%", "70%", "类型"],
    "actionSuggestions": ["区分痴呆综合征与具体病因", "由专业评估判断痴呆亚型"],
    "sourceKeys": ["who", "niaFact"]
  },
  {
    "category": "INTRODUCTION",
    "questions": ["早发型和晚发型阿尔茨海默病怎样区分？", "65岁前发病就算早发型吗？", "早发型阿尔茨海默病和晚发型有什么不同？", "青年型痴呆是什么意思？", "中年起病的阿尔茨海默病常见吗？"],
    "answer": "通常把65岁以前出现症状称为早发或青年起病，65岁及以后称为晚发。早发病例相对少见，表现不一定只以记忆下降为主；无论年龄，都需要排查其他可能原因并结合病史、认知功能和相关检查判断。",
    "keywords": ["早发型", "晚发型", "65岁", "青年起病", "中年起病"],
    "actionSuggestions": ["年轻患者持续出现认知变化时尽早就医", "早发且家族聚集时咨询遗传专科"],
    "sourceKeys": ["niaCauses", "who"]
  },
  {
    "category": "INTRODUCTION",
    "questions": ["除阿尔茨海默病外还有哪些常见痴呆？", "血管性痴呆和阿尔茨海默病有什么区别？", "路易体痴呆属于阿尔茨海默病吗？", "额颞叶痴呆与阿尔茨海默病是一种病吗？", "不同类型痴呆的表现会一样吗？"],
    "answer": "痴呆是多种疾病造成的综合征。除阿尔茨海默病外，常见类型包括血管性痴呆、路易体痴呆和额颞叶痴呆等。不同类型在起病方式、突出症状和治疗注意事项上可能不同，仅凭某一个表现通常无法准确区分。",
    "keywords": ["血管性痴呆", "路易体痴呆", "额颞叶痴呆", "痴呆类型", "鉴别"],
    "actionSuggestions": ["携带完整病史接受专科鉴别", "不要根据单一症状自行判断类型"],
    "sourceKeys": ["who", "nice"]
  },
  {
    "category": "INTRODUCTION",
    "questions": ["什么是混合性痴呆？", "阿尔茨海默病能和血管性痴呆同时存在吗？", "一个人可能同时有两种痴呆病因吗？", "混合病理是什么意思？", "阿尔茨海默病合并脑血管病常见吗？"],
    "answer": "一个人可能同时存在阿尔茨海默病相关变化和脑血管损伤等多种病理，这常被称为混合性痴呆或混合病理。各类型边界并不总是清楚，诊断需综合症状进程、体检、影像和其他检查。",
    "keywords": ["混合性痴呆", "混合病理", "脑血管病", "同时存在", "合并"],
    "actionSuggestions": ["积极管理血压、血糖、血脂等血管风险", "由医生综合判断主要病因"],
    "sourceKeys": ["who", "niaFact"]
  },
  {
    "category": "INTRODUCTION",
    "questions": ["年龄为什么是阿尔茨海默病的重要风险因素？", "年纪大就一定会得阿尔茨海默病吗？", "阿尔茨海默病风险会随年龄增加吗？", "高龄和阿尔茨海默病有什么关系？", "阿尔茨海默病是不是衰老的必然结果？"],
    "answer": "年龄是已知最重要的风险因素，风险随年龄增加，但阿尔茨海默病并不是正常衰老的必然结果，也不是每位高龄者都会患病。风险表示发生可能性变化，不能用于预测某个人一定会或不会得病。",
    "keywords": ["年龄", "高龄", "风险因素", "一定会", "正常衰老"],
    "actionSuggestions": ["关注可干预风险而非因年龄过度恐慌", "出现持续功能变化时及时评估"],
    "sourceKeys": ["niaCauses", "who"]
  },
  {
    "category": "INTRODUCTION",
    "questions": ["哪些阿尔茨海默病风险因素可以改变？", "痴呆有哪些可干预危险因素？", "生活方式会影响阿尔茨海默病风险吗？", "哪些因素与认知下降风险有关？", "阿尔茨海默病风险能完全消除吗？"],
    "answer": "与认知下降或痴呆风险相关且可干预的因素包括缺乏运动、吸烟、有害饮酒、社会孤立，以及高血压、糖尿病、肥胖、听视力损失等。改善这些因素可能降低总体风险，但不能保证完全预防阿尔茨海默病。",
    "keywords": ["可干预", "危险因素", "生活方式", "认知下降", "预防"],
    "actionSuggestions": ["与医生制定适合自身疾病状况的风险管理计划", "警惕保证百分之百预防的宣传"],
    "sourceKeys": ["who", "nhcCore"]
  },
  {
    "category": "INTRODUCTION",
    "questions": ["心血管健康和阿尔茨海默病有关吗？", "高血压会增加痴呆风险吗？", "糖尿病与认知下降有什么关系？", "脑卒中会影响痴呆风险吗？", "控制血脂对脑健康有意义吗？"],
    "answer": "心脑血管健康与认知健康密切相关。高血压、糖尿病、血脂异常、肥胖和卒中等与痴呆风险有关，控制这些疾病有助于减少血管性脑损伤并维护整体脑健康，但不能据此承诺不会发生阿尔茨海默病。",
    "keywords": ["心血管", "高血压", "糖尿病", "血脂", "卒中", "脑健康"],
    "actionSuggestions": ["按医嘱监测和管理慢性病", "不要自行停用心血管药物"],
    "sourceKeys": ["who", "nhcCore"]
  },
  {
    "category": "INTRODUCTION",
    "questions": ["听力下降会影响痴呆风险吗？", "视力不好和认知下降有关吗？", "社会孤立是不是痴呆风险因素？", "受教育程度与痴呆风险有什么关系？", "空气污染会影响认知健康吗？"],
    "answer": "听力或视力损失、社会孤立、较少的认知和教育机会以及空气污染等，被研究认为与痴呆风险有关。这些是群体层面的风险关系，并不代表单一因素会直接造成某个人患病。",
    "keywords": ["听力", "视力", "社会孤立", "教育", "空气污染", "风险"],
    "actionSuggestions": ["及时评估并矫正听视力问题", "保持力所能及的社交和认知活动"],
    "sourceKeys": ["who"]
  },
  {
    "category": "INTRODUCTION",
    "questions": ["APOE ε4基因是什么意思？", "有APOE4就一定会得阿尔茨海默病吗？", "没有APOE ε4就不会得阿尔茨海默病吗？", "APOE基因能预测阿尔茨海默病吗？", "风险基因和致病基因有什么区别？"],
    "answer": "APOE ε4属于会影响风险的遗传变异，而不是对大多数人具有确定性的诊断。携带者不一定发病，不携带者也可能发病。少数早发家族性病例可由特定罕见基因变异直接导致，两者含义不同。",
    "keywords": ["APOE", "ε4", "APOE4", "风险基因", "致病基因", "预测"],
    "actionSuggestions": ["检测前后接受遗传咨询", "不要用消费级基因结果自行诊断"],
    "sourceKeys": ["niaGenetics", "niaCauses"]
  },
  {
    "category": "INTRODUCTION",
    "questions": ["普通人需要做阿尔茨海默病基因检测吗？", "什么情况下会考虑阿尔茨海默病遗传咨询？", "网上买的阿尔茨海默病基因检测可靠吗？", "家族里多人早发应该做什么？", "阿尔茨海默病基因检测有什么局限？"],
    "answer": "阿尔茨海默病遗传检测通常不是普通人群的常规筛查。早发、家族中多名近亲相似起病等情况可由神经科或遗传专科评估；检测可能涉及不确定结果、心理和家庭影响，宜在检测前后接受遗传咨询。",
    "keywords": ["基因检测", "遗传咨询", "家族多人", "早发", "消费级检测", "局限"],
    "actionSuggestions": ["先整理家系与发病年龄", "由正规医疗机构评估检测必要性"],
    "sourceKeys": ["niaGenetics", "niaBiomarkers"]
  },
  {
    "category": "INTRODUCTION",
    "questions": ["唐氏综合征与阿尔茨海默病有什么关系？", "唐氏综合征患者更容易早发阿尔茨海默病吗？", "为什么21三体与阿尔茨海默病有关？", "唐氏综合征人群都会患阿尔茨海默病吗？", "唐氏综合征成人需要关注哪些认知变化？"],
    "answer": "唐氏综合征人群发生阿尔茨海默病相关脑变化和较早出现症状的风险较高，但并非每个人都会出现临床痴呆。评估应与本人长期功能基线比较，并由熟悉该人群的专业团队完成。",
    "keywords": ["唐氏综合征", "21三体", "早发", "认知变化", "风险"],
    "actionSuggestions": ["建立个人认知和生活能力基线", "出现持续变化时寻求专科评估"],
    "sourceKeys": ["niaCauses", "niaGenetics"]
  },
  {
    "category": "INTRODUCTION",
    "questions": ["轻度认知障碍和痴呆有什么区别？", "MCI会影响独立生活吗？", "遗忘型轻度认知障碍是什么意思？", "轻度认知障碍可能保持稳定吗？", "轻度认知障碍有可能改善吗？"],
    "answer": "轻度认知障碍（MCI）表示一个或多个认知领域低于预期，但多数日常独立能力仍相对保留；痴呆则已明显影响独立生活。MCI可能稳定、改善或进展，原因不同，需要评估可逆因素并定期随访。",
    "keywords": ["轻度认知障碍", "MCI", "痴呆区别", "独立生活", "稳定", "改善"],
    "actionSuggestions": ["完成基线认知与功能评估", "按建议复查并管理可干预因素"],
    "sourceKeys": ["niaDiagnosis", "niaFact"]
  },
  {
    "category": "INTRODUCTION",
    "questions": ["什么是阿尔茨海默病临床前期？", "没有症状也可能有阿尔茨海默病脑变化吗？", "阿尔茨海默病潜伏期有多长？", "无症状期等于已经患痴呆吗？", "生物学变化和临床症状是一回事吗？"],
    "answer": "阿尔茨海默病相关生物学变化可能在明显认知症状前多年开始，研究中常称为临床前阶段。但无症状的生物标志物变化不等同于已经出现痴呆，也不能准确预测个人何时或是否会出现症状。",
    "keywords": ["临床前期", "无症状", "潜伏期", "生物学变化", "脑变化"],
    "actionSuggestions": ["无症状筛查应先咨询专业人员", "避免把研究指标直接等同于临床诊断"],
    "sourceKeys": ["niaFact", "niaBiomarkers"]
  },
  {
    "category": "INTRODUCTION",
    "questions": ["阿尔茨海默病每个人进展速度一样吗？", "早中晚期之间有明确分界吗？", "阿尔茨海默病会一直匀速加重吗？", "为什么同一阶段患者表现差异很大？", "疾病分期主要有什么用途？"],
    "answer": "阿尔茨海默病通常逐渐进展，但速度、症状顺序和支持需求因人而异，并不一定匀速变化。早、中、晚期是便于沟通和规划照护的概括，阶段之间没有适用于所有人的精确分界。",
    "keywords": ["进展速度", "阶段分界", "匀速", "个体差异", "分期用途"],
    "actionSuggestions": ["以实际功能和安全需求调整照护", "突然恶化时排查急性原因"],
    "sourceKeys": ["niaFact", "who"]
  },
  {
    "category": "INTRODUCTION",
    "questions": ["确诊后一般还能生活多久？", "阿尔茨海默病预后能准确预测吗？", "哪些因素会影响阿尔茨海默病病程？", "诊断时间能代表真正起病时间吗？", "为什么患者生存期差别很大？"],
    "answer": "从症状出现、确诊到后续病程的时间个体差异很大，会受年龄、总体健康、合并疾病、疾病阶段和照护条件等影响。确诊时间也不等于脑部变化开始时间，因此不能用一个平均数字准确预测个人预后。",
    "keywords": ["生活多久", "预后", "病程", "生存期", "确诊时间", "个体差异"],
    "actionSuggestions": ["与随访医生讨论当前阶段和照护目标", "提前规划医疗、生活和支持安排"],
    "sourceKeys": ["niaFact", "nice"]
  },
  {
    "category": "INTRODUCTION",
    "questions": ["女性更容易患阿尔茨海默病吗？", "性别会影响痴呆风险吗？", "为什么女性痴呆患者更多？", "男女阿尔茨海默病风险一样吗？", "照护痴呆患者的主要是女性吗？"],
    "answer": "全球资料显示女性承受的痴呆疾病与照护负担更重，其中包含女性平均寿命更长等多方面因素。性别相关差异仍涉及生物、社会和医疗条件等复杂影响，不能仅凭性别判断个人风险。",
    "keywords": ["女性", "男性", "性别", "风险差异", "照护负担"],
    "actionSuggestions": ["结合个人健康因素评估风险", "家庭照护任务尽量合理分担"],
    "sourceKeys": ["who"]
  },
  {
    "category": "INTRODUCTION",
    "questions": ["认知筛查和阿尔茨海默病诊断有什么区别？", "筛查阳性就代表确诊了吗？", "社区认知初筛能诊断阿尔茨海默病吗？", "认知量表分数低一定是痴呆吗？", "为什么筛查后还要到医院进一步检查？"],
    "answer": "认知筛查用于发现可能需要进一步评估的人，不能单独确诊阿尔茨海默病。分数会受教育、语言、听视力、情绪和身体状态等影响；诊断还需病史、功能变化、体检、认知测评及必要的实验室或影像检查。",
    "keywords": ["认知筛查", "筛查阳性", "社区初筛", "量表分数", "确诊", "进一步检查"],
    "actionSuggestions": ["筛查异常后到正规医疗机构评估", "携带既往资料并由知情家属补充病史"],
    "sourceKeys": ["nhcAction", "niaDiagnosis"]
  },

  {
    "category": "SYMPTOMS",
    "questions": ["阿尔茨海默病为什么常先忘记近期事情？", "记得年轻时的事却忘记刚吃过饭正常吗？", "近期记忆下降有哪些具体表现？", "反复忘记当天安排是早期信号吗？", "提示后仍想不起刚发生的事需要评估吗？"],
    "answer": "阿尔茨海默病早期常影响新信息的学习和近期记忆，可能表现为忘记刚发生的事、当天安排或近期谈话，而较久远记忆暂时相对保留。关键要看变化是否持续、加重并影响生活，不能凭一个例子确诊。",
    "keywords": ["近期记忆", "远期记忆", "刚吃过饭", "当天安排", "提示后想不起"],
    "actionSuggestions": ["记录具体事件、频率和开始时间", "持续影响生活时接受认知评估"],
    "sourceKeys": ["niaDiagnosis", "niaFact"]
  },
  {
    "category": "SYMPTOMS",
    "questions": ["总是重复讲同一件事是什么表现？", "刚问完又问一遍可能是认知问题吗？", "反复确认约会时间需要担心吗？", "老人一天多次打电话问同一问题正常吗？", "重复购买同样物品可能与记忆下降有关吗？"],
    "answer": "短时间内重复提问、讲述或购买，可能反映没有保留刚获得的信息，是常见的近期记忆异常表现之一。焦虑、听力问题或信息没有听清也可能造成重复，应结合其他变化和生活影响评估。",
    "keywords": ["重复讲", "刚问又问", "确认时间", "多次打电话", "重复购买"],
    "actionSuggestions": ["先确认听力和沟通是否清楚", "用日历或提示板辅助并记录变化"],
    "sourceKeys": ["niaFact", "niaBehavior"]
  },
  {
    "category": "SYMPTOMS",
    "questions": ["说话越来越含糊可能是什么认知表现？", "听不懂复杂句子与阿尔茨海默病有关吗？", "写字和拼词变困难需要担心吗？", "跟不上多人对话可能是认知下降吗？", "常用错词但自己没发现属于什么变化？"],
    "answer": "阿尔茨海默病可影响找词、理解、表达、阅读或书写，使对话变慢或容易中断，但听力、疲劳、情绪和其他神经系统疾病也可造成类似问题。突然出现语言障碍应首先按急症处理。",
    "keywords": ["含糊", "理解句子", "写字", "跟不上对话", "用错词", "语言"],
    "actionSuggestions": ["区分逐渐变化还是突然发生", "逐渐加重时做语言和认知评估"],
    "sourceKeys": ["niaDiagnosis", "who"]
  },
  {
    "category": "SYMPTOMS",
    "questions": ["看不准台阶距离可能与痴呆有关吗？", "把地毯花纹当成障碍是什么表现？", "认不清物体位置属于阿尔茨海默病症状吗？", "停车总判断不好距离需要评估吗？", "视觉空间能力下降有哪些表现？"],
    "answer": "阿尔茨海默病可能影响视觉空间处理，出现距离判断、定位物体、识别复杂场景或驾驶停车困难。但白内障等眼病也很常见，应同时评估视力；突然的视觉异常需及时就医。",
    "keywords": ["距离", "台阶", "地毯", "物体位置", "停车", "视觉空间"],
    "actionSuggestions": ["先排查眼科问题", "暂停可能不安全的驾驶并接受评估"],
    "sourceKeys": ["who", "niaSafety"]
  },
  {
    "category": "SYMPTOMS",
    "questions": ["做饭步骤总弄乱可能是认知下降吗？", "完成多步骤任务变困难属于什么症状？", "不会使用原本熟悉的家电要担心吗？", "安排一天的事情越来越困难正常吗？", "计划和解决问题能力下降有哪些表现？"],
    "answer": "做饭、安排日程、使用熟悉设备或完成多步骤任务变困难，可能反映计划、排序和执行功能下降。需要与本人过去水平比较，并排除视听问题、抑郁、药物及急性身体疾病等影响。",
    "keywords": ["做饭步骤", "多步骤任务", "熟悉家电", "安排日程", "计划", "执行功能"],
    "actionSuggestions": ["暂时协助涉及火、电和复杂操作的任务", "记录具体错误供医生评估"],
    "sourceKeys": ["who", "niaFact"]
  },
  {
    "category": "SYMPTOMS",
    "questions": ["容易相信诈骗可能是判断力下降吗？", "突然乱花钱与阿尔茨海默病有关吗？", "做出明显不安全决定是什么认知表现？", "不顾天气穿错衣服属于判断问题吗？", "个人卫生明显变差可能是认知症状吗？"],
    "answer": "财务决定变差、容易受骗、忽视安全、穿着不合情境或个人卫生下降，可能反映判断力和自我管理能力变化，也可能受情绪或其他疾病影响。若涉及财产或人身安全，应尽早增加支持。",
    "keywords": ["诈骗", "乱花钱", "不安全决定", "穿错衣服", "个人卫生", "判断力"],
    "actionSuggestions": ["在尊重本人基础上加强财务和安全防护", "尽早进行专业评估"],
    "sourceKeys": ["niaFact", "nice"]
  },
  {
    "category": "SYMPTOMS",
    "questions": ["分不清今天几号是什么症状？", "昼夜颠倒和时间定向障碍有关吗？", "在自己家里找不到卫生间需要担心吗？", "忘记自己在哪里属于什么表现？", "把多年以前的事情当成今天发生正常吗？"],
    "answer": "持续分不清日期、季节、地点或事情发生的先后，可能属于时间和地点定向障碍。偶尔记错日期并不等于痴呆；如果频繁发生、在熟悉环境也困惑或出现安全风险，应尽早评估。",
    "keywords": ["日期", "昼夜", "卫生间", "在哪里", "时间定向", "地点定向"],
    "actionSuggestions": ["使用清晰时钟、日历和房间标识", "频繁迷失时加强陪同和安全措施"],
    "sourceKeys": ["who", "niaDiagnosis"]
  },
  {
    "category": "SYMPTOMS",
    "questions": ["把东西放进冰箱后找不到是什么表现？", "经常把物品放在不合常理的位置正常吗？", "丢东西后无法回想寻找步骤需要担心吗？", "总怀疑别人偷东西可能与记忆下降有关吗？", "藏东西后完全忘记属于阿尔茨海默病症状吗？"],
    "answer": "反复把物品放在异常位置、无法回溯步骤，随后因找不到而怀疑他人，可能与记忆和推理能力下降有关。也要排查环境变化、压力或精神症状，避免争辩和指责。",
    "keywords": ["放错位置", "冰箱", "找不到", "偷东西", "藏东西", "回溯步骤"],
    "actionSuggestions": ["固定重要物品位置并准备替代品", "突然出现强烈猜疑时排查身体和药物因素"],
    "sourceKeys": ["niaFact", "niaBehavior"]
  },
  {
    "category": "SYMPTOMS",
    "questions": ["对以前喜欢的事失去兴趣是阿尔茨海默病症状吗？", "越来越不愿社交可能与认知下降有关吗？", "整天坐着什么都不做是冷漠吗？", "主动性明显下降需要评估吗？", "不关心家人情绪可能是疾病变化吗？"],
    "answer": "兴趣减少、主动性下降和社交退缩可见于阿尔茨海默病，也常见于抑郁、疼痛、疲劳或听力困难。应关注变化是否持续以及是否伴随悲伤、自责、睡眠食欲改变等，进行综合评估。",
    "keywords": ["失去兴趣", "不愿社交", "冷漠", "主动性", "不关心", "退缩"],
    "actionSuggestions": ["提供简单且熟悉的活动邀请", "筛查抑郁、疼痛和听力问题"],
    "sourceKeys": ["who", "niaBehavior"]
  },
  {
    "category": "SYMPTOMS",
    "questions": ["焦虑会是阿尔茨海默病的早期表现吗？", "记忆下降后总是害怕正常吗？", "抑郁和认知下降怎样区分？", "情绪低落会让记忆测验变差吗？", "阿尔茨海默病患者为什么容易烦躁不安？"],
    "answer": "焦虑、悲伤、烦躁可能伴随认知变化，也会在本人意识到能力下降时出现；抑郁本身也能影响注意和记忆。两者可并存，不能仅凭情绪或一次认知测验区分。",
    "keywords": ["焦虑", "害怕", "抑郁", "情绪低落", "烦躁", "记忆测验"],
    "actionSuggestions": ["同时评估情绪、睡眠和认知功能", "出现自伤想法时立即寻求急诊帮助"],
    "sourceKeys": ["who", "niaDiagnosis"]
  },
  {
    "category": "SYMPTOMS",
    "questions": ["阿尔茨海默病患者突然变得爱发脾气正常吗？", "无故大喊大叫可能是什么原因？", "推人打人一定是病情加重吗？", "来回踱步属于行为症状吗？", "拒绝照护并激动可能由什么引起？"],
    "answer": "易怒、喊叫、踱步、抵抗照护或攻击行为可能与认知障碍有关，但常由疼痛、感染、便秘、饥渴、环境嘈杂、沟通不当或药物变化诱发。突然改变应先排查急性身体原因和安全风险。",
    "keywords": ["发脾气", "喊叫", "打人", "踱步", "拒绝照护", "激动"],
    "actionSuggestions": ["记录行为前后的诱因", "先保证安全并排查疼痛感染等原因"],
    "sourceKeys": ["niaBehavior", "nice"]
  },
  {
    "category": "SYMPTOMS",
    "questions": ["看见不存在的人是阿尔茨海默病症状吗？", "总说有人要害他属于妄想吗？", "怀疑配偶不忠可能与痴呆有关吗？", "阿尔茨海默病会出现幻听吗？", "幻觉突然明显增多需要就医吗？"],
    "answer": "部分痴呆患者会出现幻觉、妄想或强烈猜疑，但这些表现也可能提示其他痴呆类型、谵妄、感染、视听障碍或药物影响。若突然出现、造成恐惧或有伤害风险，应尽快就医。",
    "keywords": ["看见不存在", "妄想", "有人要害", "怀疑配偶", "幻听", "幻觉"],
    "actionSuggestions": ["不要正面争辩，先安抚并保证安全", "突然出现或有风险时尽快医疗评估"],
    "sourceKeys": ["niaBehavior", "niaFact"]
  },
  {
    "category": "SYMPTOMS",
    "questions": ["一到傍晚就更糊涂是怎么回事？", "什么是阿尔茨海默病的日落现象？", "晚上焦躁白天正常可能与痴呆有关吗？", "昼夜节律改变属于阿尔茨海默病症状吗？", "夜间频繁起床游走需要注意什么？"],
    "answer": "有些患者在傍晚或夜间更混乱、焦躁或游走，常称为日落现象。疲劳、光线不足、环境变化、睡眠紊乱、疼痛和疾病都可能加重；新近或突然的夜间变化仍需排查谵妄等急性原因。",
    "keywords": ["傍晚", "日落现象", "晚上焦躁", "昼夜节律", "夜间游走"],
    "actionSuggestions": ["保持白天活动、规律作息和傍晚充足照明", "突然加重时就医排查急性原因"],
    "sourceKeys": ["niaBehavior", "niaCaregiving"]
  },
  {
    "category": "SYMPTOMS",
    "questions": ["不会自己穿衣属于哪个阶段的表现？", "忘记洗澡刷牙说明生活能力下降吗？", "需要帮助吃饭是阿尔茨海默病加重吗？", "工具性生活能力和基本生活能力有什么区别？", "从不会管钱到不会如厕说明什么？"],
    "answer": "疾病常先影响购物、做饭、管钱、用药等工具性生活能力，随后可能影响穿衣、洗澡、进食和如厕等基本生活能力。能力变化可帮助判断支持需求，但不能仅凭一项活动精确分期。",
    "keywords": ["穿衣", "洗澡", "刷牙", "吃饭", "工具性生活能力", "基本生活能力", "如厕"],
    "actionSuggestions": ["只在需要的环节提供分步骤协助", "定期评估照护强度和居家安全"],
    "sourceKeys": ["who", "niaFact"]
  },
  {
    "category": "SYMPTOMS",
    "questions": ["阿尔茨海默病晚期会走路困难吗？", "频繁跌倒属于阿尔茨海默病症状吗？", "尿失禁可能在疾病晚期出现吗？", "长期卧床是晚期表现吗？", "动作越来越慢一定是阿尔茨海默病吗？"],
    "answer": "疾病晚期可能出现行走和转移困难、失禁、卧床及完全依赖，但跌倒或动作变慢还可能来自药物、肌力下降、关节病、卒中等。任何突然的行动能力下降都不应简单归因于自然进展。",
    "keywords": ["走路困难", "跌倒", "尿失禁", "卧床", "动作慢", "晚期"],
    "actionSuggestions": ["评估跌倒、药物和身体疾病因素", "请康复或护理人员指导安全转移"],
    "sourceKeys": ["who", "niaLate"]
  },
  {
    "category": "SYMPTOMS",
    "questions": ["吃饭越来越少可能与阿尔茨海默病有关吗？", "含着食物不咽是什么信号？", "喝水总呛咳需要担心吗？", "不明原因体重下降是晚期症状吗？", "吞咽困难会带来哪些风险？"],
    "answer": "兴趣下降、不会使用餐具、口腔问题或吞咽困难都可能让进食减少。含食、呛咳、声音变湿或体重下降提示需要评估，吞咽困难可增加脱水、营养不良和吸入性肺炎风险。",
    "keywords": ["吃饭少", "含食", "呛咳", "体重下降", "吞咽困难", "吸入"],
    "actionSuggestions": ["尽快联系医生和吞咽或营养专业人员", "明显窒息或呼吸困难时立即急救"],
    "sourceKeys": ["niaLate", "niaCaregiving"]
  },
  {
    "category": "SYMPTOMS",
    "questions": ["认不出家人是阿尔茨海默病的表现吗？", "会用物品却叫不出名字属于什么症状？", "不知道牙刷怎么用可能是什么问题？", "穿衣顺序完全不会了属于失用吗？", "照镜子认不出自己可能与痴呆有关吗？"],
    "answer": "随着疾病进展，部分患者可能难以识别人或物、说出名称，或无法完成原本熟练的动作，这可涉及失认、命名困难或失用等认知功能变化。视听障碍和其他神经疾病也需要排查。",
    "keywords": ["认不出家人", "叫不出名字", "不会用牙刷", "失用", "失认", "镜子"],
    "actionSuggestions": ["用姓名、照片和分步骤提示协助", "进行视听与神经认知综合评估"],
    "sourceKeys": ["niaFact", "niaDiagnosis"]
  },
  {
    "category": "SYMPTOMS",
    "questions": ["认知在几小时内变差像阿尔茨海默病吗？", "发烧后突然不认识人可能是谵妄吗？", "阿尔茨海默病通常会突然恶化吗？", "住院后突然昼夜颠倒需要怎么判断？", "哪些突然症状不能等记忆门诊？"],
    "answer": "阿尔茨海默病通常逐渐进展；数小时到数天内明显波动或突然混乱更要警惕谵妄、感染、脱水、低血糖、药物或卒中等急性原因。伴意识异常、抽搐、突发语言障碍或单侧无力应立即拨打120。",
    "keywords": ["几小时", "发烧", "谵妄", "突然恶化", "住院", "不能等"],
    "actionSuggestions": ["尽快线下急诊评估并携带用药清单", "有卒中或意识异常信号时立即拨打120"],
    "sourceKeys": ["niaDiagnosis", "who"]
  },

  {
    "category": "COPING",
    "questions": ["认知量表一般会测哪些能力？", "做简易精神状态检查能确诊吗？", "认知测试前需要特别复习吗？", "教育程度会影响认知测验结果吗？", "为什么医生会重复做认知评估？"],
    "answer": "认知测评可涉及记忆、注意、语言、计算、视空间和执行功能。它是综合评估的一部分，不能单独确诊；结果会受教育、语言、文化、听视力、疲劳和情绪影响，重复评估有助于观察变化趋势。",
    "keywords": ["认知量表", "简易精神状态", "认知测试", "教育程度", "重复评估", "测验"],
    "actionSuggestions": ["按平常状态参加测试，无需背题", "告知评估者语言、教育和听视力情况"],
    "sourceKeys": ["niaDiagnosis", "nice"]
  },
  {
    "category": "COPING",
    "questions": ["评估记忆下降为什么要验血？", "诊断阿尔茨海默病一定要做核磁吗？", "CT和MRI在认知评估中有什么作用？", "甲状腺和维生素检查与记忆有什么关系？", "医生为什么要排查抑郁和药物副作用？"],
    "answer": "血液检查、身体和神经系统检查可帮助寻找感染、代谢、甲状腺、营养或药物等可处理因素；CT或MRI可发现卒中、肿瘤等其他脑部问题并提供支持信息。具体项目应按个人情况选择，并非人人相同。",
    "keywords": ["验血", "核磁", "MRI", "CT", "甲状腺", "维生素", "药物副作用"],
    "actionSuggestions": ["由医生根据病史选择检查", "不要因单个影像描述自行诊断"],
    "sourceKeys": ["niaFact", "niaDiagnosis"]
  },
  {
    "category": "COPING",
    "questions": ["阿尔茨海默病生物标志物有哪些？", "淀粉样蛋白PET什么时候可能需要做？", "腰穿检查能帮助诊断阿尔茨海默病吗？", "血液生物标志物能单独确诊吗？", "所有记忆下降患者都要做PET或腰穿吗？"],
    "answer": "可用于专业评估的生物标志物包括特定PET影像、脑脊液中的淀粉样蛋白和tau，以及正在发展的血液指标。它们只能作为完整评估的一部分，适用性、可及性和准确性不同，通常不是所有人都必须做。",
    "keywords": ["生物标志物", "淀粉样PET", "腰穿", "脑脊液", "血液标志物", "tau"],
    "actionSuggestions": ["在认知专科讨论检查是否会改变诊疗决策", "通过正规医疗机构检测和解释结果"],
    "sourceKeys": ["niaBiomarkers", "nice"]
  },
  {
    "category": "COPING",
    "questions": ["确诊后怎样制定治疗目标？", "阿尔茨海默病需要多久复诊一次？", "治疗效果应该看量表还是生活能力？", "为什么治疗计划要因人而异？", "确诊后家属首先应该做什么？"],
    "answer": "治疗和照护应围绕患者价值与实际需求，综合药物、慢病管理、活动与康复、安全、心理社会支持和照护者支持。复诊频率由病情和用药决定，效果既看症状与量表，也要看日常功能、舒适和安全。",
    "keywords": ["治疗目标", "复诊", "治疗效果", "个体化", "确诊后", "生活能力"],
    "actionSuggestions": ["与患者共同确定近期优先目标", "保存复诊记录并记录功能与副作用变化"],
    "sourceKeys": ["who", "nice"]
  },
  {
    "category": "COPING",
    "questions": ["胆碱酯酶抑制剂有什么作用？", "多奈哌齐能治愈阿尔茨海默病吗？", "美金刚一般用于什么阶段？", "认知症状药物多久能看出效果？", "吃认知药后恶心头晕怎么办？"],
    "answer": "部分药物可在适合的患者中帮助维持或改善一段时间的认知、功能或行为症状，但不能根治。适用阶段、副作用和禁忌各不相同；出现恶心、食欲下降、头晕、晕厥等情况应联系处方医生，不能自行加减或停药。",
    "keywords": ["胆碱酯酶抑制剂", "多奈哌齐", "美金刚", "药效", "恶心", "头晕"],
    "actionSuggestions": ["按处方服药并记录效果和副作用", "严重不适或晕厥及时就医"],
    "sourceKeys": ["niaTreatment", "who"]
  },
  {
    "category": "COPING",
    "questions": ["抗淀粉样蛋白药适合所有阿尔茨海默病患者吗？", "疾病修饰治疗和改善症状药有什么区别？", "使用抗淀粉样蛋白治疗前要确认什么？", "ARIA脑水肿或微出血是什么意思？", "国内能否使用某种新药应该看什么信息？"],
    "answer": "部分抗淀粉样蛋白治疗只适用于经过严格评估、处于特定早期阶段且确认相关病理的人群，可能带来脑水肿或出血等风险并需要影像监测。药品批准范围和可及性会随国家地区变化，应以当地监管信息和专科医生判断为准。",
    "keywords": ["抗淀粉样蛋白", "疾病修饰", "ARIA", "脑水肿", "微出血", "新药"],
    "actionSuggestions": ["到具备评估和监测能力的专科咨询", "核对当地最新批准说明而非自行购药"],
    "sourceKeys": ["niaTreatment", "niaBiomarkers"]
  },
  {
    "category": "COPING",
    "questions": ["怎样避免阿尔茨海默病患者漏服或重复吃药？", "家属能把所有药都研碎喂吗？", "保健品能和处方药一起吃吗？", "老年患者多种药物怎样管理更安全？", "患者拒绝吃药应该强行喂吗？"],
    "answer": "可用药盒、清单和固定流程，由一名可靠照护者核对；定期让医生或药师审查全部处方药、非处方药和保健品。药片能否掰开或研碎取决于剂型，拒药也可能与吞咽、味道或不适有关，不宜擅自处理。",
    "keywords": ["漏服", "重复吃药", "研碎", "保健品", "多种药物", "拒绝吃药"],
    "actionSuggestions": ["建立单一更新版本的用药清单", "改变剂型或给药方式前咨询医生或药师"],
    "sourceKeys": ["niaTreatment", "niaCaregiving"]
  },
  {
    "category": "COPING",
    "questions": ["患者激动时应该先用镇静药吗？", "怎样不用药处理阿尔茨海默病行为问题？", "出现攻击行为时家属怎样保证安全？", "抗精神病药用于痴呆要注意什么？", "怎样寻找行为问题背后的诱因？"],
    "answer": "处理激动、攻击或其他行为症状时，应先保证安全并寻找疼痛、感染、便秘、药物、噪声或沟通等诱因，优先尝试环境和照护调整。药物可能有严重风险，只应由医生在明确需要、权衡利弊并监测时使用。",
    "keywords": ["镇静药", "不用药", "攻击行为", "抗精神病药", "行为问题", "诱因"],
    "actionSuggestions": ["记录时间、环境和行为前后事件", "有立即伤害风险时撤离危险物并求助急救"],
    "sourceKeys": ["who", "nice", "niaBehavior"]
  },
  {
    "category": "COPING",
    "questions": ["和阿尔茨海默病患者说话要放慢吗？", "患者说错事情需要不断纠正吗？", "怎样给认知障碍患者提问更容易回答？", "听不懂患者表达时应该怎么办？", "怎样用非语言方式帮助沟通？"],
    "answer": "沟通时面对患者、减少背景噪声，一次说一件事并给足反应时间；使用简短句子、二选一和手势示范。对无关安全的错误不必反复争辩，可先回应情绪，再温和引导。",
    "keywords": ["放慢", "纠正", "提问", "听不懂", "非语言", "沟通"],
    "actionSuggestions": ["确认眼镜和助听器正常使用", "保持尊重，避免像谈论不在场的人一样谈患者"],
    "sourceKeys": ["niaBehavior", "niaCaregiving"]
  },
  {
    "category": "COPING",
    "questions": ["怎样为阿尔茨海默病患者安排规律作息？", "哪些活动适合轻度阿尔茨海默病患者？", "认知刺激活动越难越好吗？", "患者做家务很慢还应该让他参与吗？", "怎样避免活动让患者挫败？"],
    "answer": "规律作息和与能力匹配的熟悉活动有助于维持参与感与生活质量。可把任务拆小、减少选择并提供适量提示，重视过程和愉快体验，而不是追求成绩或强迫完成高难度训练。",
    "keywords": ["规律作息", "适合活动", "认知刺激", "做家务", "挫败", "参与"],
    "actionSuggestions": ["保留患者仍能安全完成的步骤", "疲劳或烦躁时缩短或更换活动"],
    "sourceKeys": ["who", "niaCaregiving"]
  },
  {
    "category": "COPING",
    "questions": ["运动能降低阿尔茨海默病风险吗？", "预防认知下降应该怎么吃？", "戒烟对脑健康有帮助吗？", "饮酒能预防阿尔茨海默病吗？", "控制血压血糖怎样帮助认知健康？"],
    "answer": "规律身体活动、健康均衡饮食、不吸烟、避免有害饮酒、保持健康体重并管理血压、血糖和血脂，有助于降低认知下降和痴呆的总体风险。没有某种食物、酒或保健品能保证预防。",
    "keywords": ["运动", "饮食", "戒烟", "饮酒", "血压", "血糖", "降低风险"],
    "actionSuggestions": ["结合年龄和慢病情况制定可持续计划", "运动前有心肺或跌倒风险者先咨询医生"],
    "sourceKeys": ["who", "nhcCore"]
  },
  {
    "category": "COPING",
    "questions": ["怎样逐个房间检查痴呆患者的居家安全？", "家里有阿尔茨海默病患者怎样防跌倒？", "燃气灶对认知下降患者怎样管理？", "浴室怎样改造更安全？", "药品和清洁剂应该怎样存放？"],
    "answer": "应按房间评估照明、地面、楼梯、浴室、火源、燃气、电器、药物和有毒物品风险。可增加扶手、防滑和清晰标识，把危险物上锁，并随着能力变化定期复查；改造应尽量兼顾自主和尊严。",
    "keywords": ["房间检查", "防跌倒", "燃气灶", "浴室", "药品", "清洁剂", "居家安全"],
    "actionSuggestions": ["优先处理松动扶手、差照明和火源等立即危险", "每次功能变化后重新检查环境"],
    "sourceKeys": ["niaSafety", "niaCaregiving"]
  },
  {
    "category": "COPING",
    "questions": ["患者走失后家属第一步做什么？", "怎样给容易迷路的老人准备身份信息？", "定位设备能完全防止走失吗？", "夜间总想出门怎样降低风险？", "发现患者有游走迹象应该怎样制定预案？"],
    "answer": "走失预防可结合规律活动、门口提示、身份联系卡、近期照片和适当定位工具，并提前与家人和社区制定寻找预案。技术设备不能替代看护；一旦失联应立即按当地流程报警和组织寻找，不必等待。",
    "keywords": ["走失后", "身份信息", "定位设备", "夜间出门", "游走", "寻找预案"],
    "actionSuggestions": ["保存近期正面照片和紧急联系人信息", "失联后立即报警并告知常去地点"],
    "sourceKeys": ["niaBehavior", "niaSafety"]
  },
  {
    "category": "COPING",
    "questions": ["阿尔茨海默病患者什么时候应停止开车？", "怎样和患者谈驾驶安全？", "患者容易被骗时怎样保护财务？", "确诊后为什么要尽早做法律和财务规划？", "患者还能参与医疗和生活决定吗？"],
    "answer": "驾驶、财务和重大决定应依据实际能力和风险，而非只看诊断名称。应在患者仍能表达意愿时共同规划，采取逐步、最少限制的支持，并遵守当地驾驶、代理和法律规定；高风险活动需及时暂停。",
    "keywords": ["停止开车", "驾驶安全", "财务", "法律规划", "参与决定", "能力"],
    "actionSuggestions": ["记录具体驾驶或财务风险事例并请专业人员评估", "尽早了解当地有效的授权与预先规划方式"],
    "sourceKeys": ["niaCaregiving", "nice"]
  },
  {
    "category": "COPING",
    "questions": ["吞咽困难时怎样调整吃饭方式？", "患者总把食物含在嘴里怎么办？", "怎样降低进食呛咳风险？", "阿尔茨海默病患者口腔护理为什么重要？", "体重持续下降应该找哪些专业人员？"],
    "answer": "进食困难时应先评估牙齿、口腔、吞咽和药物等原因，可由医生、吞咽治疗和营养专业人员给出适合的食物质地、姿势和营养方案。不要在没有评估时强行喂食或自行长期改变稠度。",
    "keywords": ["调整吃饭", "含食", "呛咳", "口腔护理", "体重下降", "吞咽"],
    "actionSuggestions": ["保持坐直、小口慢食并确认吞咽", "窒息、呼吸困难或反复发热时及时就医"],
    "sourceKeys": ["niaLate", "niaCaregiving"]
  },
  {
    "category": "COPING",
    "questions": ["患者拒绝洗澡应该怎么办？", "怎样帮助阿尔茨海默病患者穿衣？", "如厕失禁时怎样维护患者尊严？", "照护时患者害怕触碰怎么办？", "怎样让日常个人护理更顺利？"],
    "answer": "个人护理可固定熟悉时间和照护者，提前解释，一次提示一个步骤，并让患者保留能完成的部分。先排查疼痛、寒冷、隐私和环境刺激；尊重拒绝，必要时稍后再试，避免强迫和羞辱。",
    "keywords": ["拒绝洗澡", "帮助穿衣", "失禁", "害怕触碰", "个人护理", "尊严"],
    "actionSuggestions": ["准备简单衣物并保证温度、隐私和防滑", "突然抗拒护理时排查疼痛或感染"],
    "sourceKeys": ["niaCaregiving", "niaSafety"]
  },
  {
    "category": "COPING",
    "questions": ["照护者怎样判断自己需要休息？", "家人不愿分担照护怎么办？", "什么是喘息照护？", "怎样寻找社区或居家照护支持？", "照护者出现抑郁和失眠应该怎么办？"],
    "answer": "持续疲惫、失眠、易怒、绝望或健康恶化提示照护负担过重。可把任务具体分工，使用亲友、社区、日间照护、居家服务或喘息服务，并维护照护者自己的就医和休息；危机时应立即求助。",
    "keywords": ["需要休息", "分担照护", "喘息照护", "社区支持", "抑郁", "失眠"],
    "actionSuggestions": ["列出可交给他人的具体任务并安排固定轮休", "有自伤想法或无法保障安全时立即寻求急诊帮助"],
    "sourceKeys": ["niaCaregiving", "who"]
  },
  {
    "category": "COPING",
    "questions": ["阿尔茨海默病晚期照护重点是什么？", "长期卧床怎样预防压疮？", "晚期患者怎样安全翻身和转移？", "什么时候可以讨论舒缓或安宁疗护？", "家属怎样为生命末期照护做准备？"],
    "answer": "晚期照护重点包括舒适、疼痛和症状处理、安全移动、皮肤与口腔护理、营养吞咽评估以及支持家属。翻身转移应由专业人员指导；舒缓和预先照护讨论可根据患者价值和需要及早开展。",
    "keywords": ["晚期照护", "压疮", "翻身", "转移", "舒缓医疗", "安宁疗护", "生命末期"],
    "actionSuggestions": ["请医疗护理或康复人员示范安全照护", "与团队讨论患者意愿、舒适目标和紧急计划"],
    "sourceKeys": ["niaLate", "nice"]
  }
]
'@ | ConvertFrom-Json

$expectedClustersPerCategory = 18
$newDocuments = @()
$categoryOffsets = @{ INTRODUCTION = 31; SYMPTOMS = 121; COPING = 211 }

foreach ($category in @('INTRODUCTION', 'SYMPTOMS', 'COPING')) {
    $categoryClusters = @($clusters | Where-Object { $_.category -eq $category })
    if ($categoryClusters.Count -ne $expectedClustersPerCategory) {
        throw "Expected $expectedClustersPerCategory clusters for $category, found $($categoryClusters.Count)."
    }

    $nextId = $categoryOffsets[$category]
    foreach ($cluster in $categoryClusters) {
        if (@($cluster.questions).Count -ne 5) {
            throw "Every cluster must contain exactly five questions: $($cluster.questions -join ' / ')"
        }

        $sources = @($cluster.sourceKeys | ForEach-Object {
            if (-not $sourceCatalog.ContainsKey($_)) {
                throw "Unknown source key: $_"
            }
            $sourceCatalog[$_]
        })

        foreach ($question in $cluster.questions) {
            $newDocuments += [pscustomobject][ordered]@{
                id = 'K{0:D3}' -f $nextId
                category = $category
                question = $question
                answer = $cluster.answer
                keywords = @($cluster.keywords)
                actionSuggestions = @($cluster.actionSuggestions)
                sources = $sources
            }
            $nextId++
        }
    }
}

$documents = @($baseDocuments) + $newDocuments
$duplicateIds = @($documents | Group-Object id | Where-Object Count -gt 1)
$duplicateQuestions = @($documents | Group-Object question | Where-Object Count -gt 1)
if ($duplicateIds.Count -gt 0 -or $duplicateQuestions.Count -gt 0) {
    throw 'Generated knowledge base contains duplicate IDs or exact duplicate questions.'
}

foreach ($category in @('INTRODUCTION', 'SYMPTOMS', 'COPING')) {
    $count = @($documents | Where-Object { $_.category -eq $category }).Count
    if ($count -ne 100) {
        throw "Expected 100 documents for $category, found $count."
    }
}

$json = $documents | ConvertTo-Json -Depth 10
$utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($knowledgePath, $json + [Environment]::NewLine, $utf8WithoutBom)

Write-Output "Generated $($documents.Count) knowledge documents at $knowledgePath"
