package com.alz.service.impl;

import com.alz.dto.AssistantChatResponse;
import com.alz.dto.AssistantSource;
import com.alz.dto.ScreeningGuideResponse;
import com.alz.service.AssistantService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class AssistantServiceImpl implements AssistantService {

    private static final String DISCLAIMER =
            "本助手仅提供健康科普和风险筛查提示，不能诊断、排除或治疗阿尔茨海默病。请由正规医疗机构结合病史、认知与功能评估、体格检查及必要的辅助检查作出判断。";

    private static final AssistantSource NHC_CORE = new AssistantSource(
            "国家卫生健康委：阿尔茨海默病预防与干预核心信息",
            "https://www.nhc.gov.cn/lljks/c100158/201909/c124c2c91fb74701b11d560aba0ad827.shtml"
    );
    private static final AssistantSource NIA_SIGNS = new AssistantSource(
            "美国国家老龄研究所：阿尔茨海默病的常见表现",
            "https://www.nia.nih.gov/health/alzheimers-symptoms-and-diagnosis/what-are-signs-alzheimers-disease"
    );
    private static final AssistantSource NIA_DIAGNOSIS = new AssistantSource(
            "美国国家老龄研究所：痴呆的症状、类型与诊断",
            "https://www.nia.nih.gov/health/alzheimers-and-dementia/what-dementia-symptoms-types-and-diagnosis"
    );
    private static final AssistantSource WHO_DEMENTIA = new AssistantSource(
            "世界卫生组织：Dementia fact sheet",
            "https://www.who.int/news-room/fact-sheets/detail/dementia"
    );

    @Override
    public AssistantChatResponse chat(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }

        String normalized = message.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");

        if (containsAny(normalized, "突然说不清", "突然不能说话", "突然口齿不清", "口角歪斜",
                "一侧无力", "单侧无力", "突然昏迷", "意识不清")) {
            return response(
                    "emergency",
                    "请立即处理急症信号",
                    "突然出现语言障碍、口角歪斜、单侧肢体无力或意识异常，可能是卒中等急症，不应等待语音筛查结果。请立即拨打 120，并记录症状最早出现的时间。不要自行驾车就医。",
                    List.of("立即拨打 120", "记录症状开始时间", "保持呼吸道通畅并等待专业救援"),
                    List.of(NIA_DIAGNOSIS),
                    true
            );
        }

        if (containsAny(normalized, "语音", "筛查", "检测", "准确率", "录音", "测一测")) {
            return response(
                    "speech_screening",
                    "语音筛查能做什么",
                    "语音可以用于发现表达流畅度、停顿、词汇使用和叙事组织等方面的风险信号，但单次录音不能确诊或排除阿尔茨海默病。方言、教育经历、听力、疲劳、情绪、其他疾病以及录音质量都可能影响结果。更适合把它作为“是否需要进一步专业评估”的提示。",
                    List.of("在安静环境中按正常状态录音", "风险升高时预约记忆门诊、神经内科或老年医学科", "保留多次结果供医生了解变化趋势"),
                    List.of(NIA_DIAGNOSIS, NHC_CORE),
                    false
            );
        }

        if (containsAny(normalized, "确诊", "诊断", "医院", "挂什么科", "检查")) {
            return response(
                    "diagnosis",
                    "如何进一步就医评估",
                    "阿尔茨海默病不能只靠一段语音或一个量表确诊。正规评估通常会了解症状和用药史，评估认知与日常功能，进行体格和神经系统检查，并按需要安排化验或脑影像，以排查抑郁、睡眠问题、药物副作用、甲状腺异常、维生素缺乏等可能原因。",
                    List.of("优先预约记忆门诊、神经内科、老年医学科或精神心理专科", "由熟悉情况的家属陪同并携带用药清单", "记录症状何时开始、是否持续加重及对生活的影响"),
                    List.of(NIA_DIAGNOSIS),
                    false
            );
        }

        if (containsAny(normalized, "症状", "表现", "忘事", "记忆", "找不到词", "迷路")) {
            return response(
                    "symptoms",
                    "哪些变化值得关注",
                    "值得关注的是持续或逐渐加重、并影响日常生活的变化，例如反复忘记近期信息、重复提问、熟悉地点迷路、处理账单或熟悉任务变困难、找词和表达困难，以及判断力、情绪或性格明显改变。偶尔忘记名字但稍后能想起，并不等同于痴呆。",
                    List.of("记录变化出现的时间和频率", "比较其与本人过去能力的差异", "若影响生活或持续加重，尽早接受专业评估"),
                    List.of(NIA_SIGNS, NIA_DIAGNOSIS),
                    false
            );
        }

        if (containsAny(normalized, "预防", "风险", "运动", "饮食", "睡眠", "血压")) {
            return response(
                    "risk_reduction",
                    "可以做的风险管理",
                    "没有任何方法能保证预防阿尔茨海默病，但规律运动、戒烟限酒、均衡饮食、保持社交和认知活动、保护听力、保证睡眠，并规范管理血压、血糖和血脂，有助于维护脑健康和降低可干预风险。不要用保健品替代正规治疗。",
                    List.of("根据身体情况制定可持续的运动计划", "定期监测并管理慢性病", "听力或睡眠问题及时就医"),
                    List.of(WHO_DEMENTIA, NHC_CORE),
                    false
            );
        }

        if (containsAny(normalized, "照护", "护理", "家属", "走失", "沟通", "照顾")) {
            return response(
                    "caregiving",
                    "照护与沟通建议",
                    "沟通时一次只说一件事，使用简短句子并给对方足够反应时间；保持熟悉、规律的作息和环境；对反复提问尽量避免争辩。若有走失风险，应加强门窗与定位信息管理。照护者也需要轮休和支持。",
                    List.of("建立固定作息和醒目标识", "保存紧急联系人与近期照片", "行为突然恶化时先排查疼痛、感染、便秘或药物变化并就医"),
                    List.of(NHC_CORE, WHO_DEMENTIA),
                    false
            );
        }

        if (containsAny(normalized, "治疗", "药物", "能治好吗", "治愈")) {
            return response(
                    "treatment",
                    "治疗需要个体化评估",
                    "目前的治疗目标包括减轻症状、延缓功能下降、处理伴随的情绪和行为问题，以及支持患者和照护者。部分治疗只适用于经过严格评估的特定人群，也可能带来重要风险，因此不能根据网络信息自行购药、停药或调整剂量。",
                    List.of("携带完整用药清单就诊", "询问医生预期获益、风险和监测要求", "出现明显副作用或状态突变时及时联系医生"),
                    List.of(NIA_DIAGNOSIS, NHC_CORE),
                    false
            );
        }

        return response(
                "overview",
                "我可以怎样帮助你",
                "我可以解释阿尔茨海默病的常见表现、语音筛查的能力边界、就医评估、风险管理、治疗常识和家庭照护。你也可以直接描述正在担心的变化，我会帮助整理下一步行动，但不会给出诊断结论。",
                supportedTopics(),
                List.of(NHC_CORE, NIA_DIAGNOSIS),
                false
        );
    }

    @Override
    public ScreeningGuideResponse screeningGuide() {
        return new ScreeningGuideResponse(
                "语音风险筛查采集指引",
                "采集相对自然、可比较的语言样本，辅助发现是否需要进一步认知评估。",
                "这些任务不是经过本系统临床验证的诊断量表，结果只能作为风险提示，不能用于确诊、排除疾病或自行调整治疗。",
                List.of(
                        "先取得本人知情同意；若无法理解并同意，不应录音",
                        "选择安静环境，确认麦克风正常，并尽量使用本人最熟悉的语言或方言",
                        "如实记录听力、情绪、睡眠、饮酒、近期感染和用药变化等可能影响表现的因素",
                        "不要提示答案，也不要反复训练后只上传最好的一次"
                ),
                List.of(
                        new ScreeningGuideResponse.ScreeningTask(1, "自然表达", "请介绍今天做过的一件事，按自己的节奏连续讲述。", "约 30—60 秒"),
                        new ScreeningGuideResponse.ScreeningTask(2, "图片叙述", "观察系统提供的图片，说出你看到的人物、事件及它们之间的关系。", "约 60—90 秒"),
                        new ScreeningGuideResponse.ScreeningTask(3, "延迟复述", "间隔一段时间后，用自己的话复述刚才听到的简短内容；不要追求逐字重复。", "约 30—60 秒")
                ),
                List.of(
                        "一次低分可能来自方言、教育经历、听力、疲劳、焦虑或设备噪声",
                        "关注同一人在相似条件下的变化趋势，而不是与他人简单比较",
                        "风险提示升高或家属持续担忧时，应接受正规医疗评估"
                ),
                List.of(
                        "突然出现说话困难、口角歪斜、单侧无力或意识异常：立即拨打 120",
                        "受试者明显痛苦、拒绝继续或无法保持安全时：立即停止",
                        "认知或生活能力近期快速下降时：尽快线下就医"
                ),
                "语音和转写文本属于敏感健康信息。应最小化采集范围，明确保存期限和用途，限制访问权限，并向用户提供查询与删除途径。"
        );
    }

    @Override
    public List<String> supportedTopics() {
        return List.of("常见表现", "语音筛查", "就医评估", "风险管理", "治疗常识", "家庭照护");
    }

    private AssistantChatResponse response(
            String intent,
            String title,
            String answer,
            List<String> suggestions,
            List<AssistantSource> sources,
            boolean urgent
    ) {
        return new AssistantChatResponse(intent, title, answer, suggestions, DISCLAIMER, sources, urgent);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
