---
name: ad-question-router
description: Classify a user's Alzheimer's disease education or consultation question into disease introduction, common symptoms, or coping methods before retrieval. Use when routing Chinese AD questions, selecting a RAG knowledge domain, or detecting emergency and out-of-scope requests.
---

# AD Question Router

Classify one current user question before knowledge retrieval. Keep routing deterministic and concise; do not answer the medical question in this skill.

## Workflow

1. Read [references/taxonomy.md](references/taxonomy.md).
2. Normalize whitespace and common names such as“阿尔茨海默病”“AD”“老年痴呆”.
3. Check `emergency` signals before all knowledge categories.
4. Select exactly one category from `introduction`, `symptoms`, `coping`, `emergency`, or `out_of_scope`.
5. Return only the JSON object defined below.

## Output

```json
{
  "category": "symptoms",
  "confidence": 0.88,
  "urgent": false,
  "matched_signals": ["反复忘记刚发生的事"],
  "reason": "问题在询问可能的认知和记忆表现"
}
```

Use a confidence from 0 to 1. Set `urgent` to `true` only for `emergency`. Never infer a diagnosis, recommend prescription changes, or treat a risk-screening result as a diagnosis.
