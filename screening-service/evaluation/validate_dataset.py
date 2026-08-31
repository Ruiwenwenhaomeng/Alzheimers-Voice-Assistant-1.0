from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

from evaluation.evaluate import load_jsonl


ALLOWED_SPLITS = {"train", "validation", "test", "external_test"}
ALLOWED_LEGAL_BASES = {"EXPLICIT_CONSENT", "IRB_WAIVER", "PUBLIC_LICENSE"}
AUDIT_FIELDS = ("language", "age_group", "education_group", "sex", "device")


@dataclass(frozen=True)
class DatasetValidation:
    passed: bool
    rows: int
    subjects: int
    split_rows: dict[str, int]
    split_subjects: dict[str, int]
    errors: list[str]
    warnings: list[str]


def validate_rows(rows: list[dict[str, Any]]) -> DatasetValidation:
    errors: list[str] = []
    warnings: list[str] = []
    recording_ids: set[str] = set()
    subject_splits: dict[str, set[str]] = defaultdict(set)
    split_rows: Counter[str] = Counter()
    split_subjects: dict[str, set[str]] = defaultdict(set)
    test_labels: Counter[int] = Counter()

    if not rows:
        errors.append("数据清单不能为空")

    for line_number, row in enumerate(rows, start=1):
        prefix = f"第 {line_number} 行"
        recording_id = _required_text(row, "recording_id", prefix, errors)
        subject_id = _required_text(row, "subject_id", prefix, errors)
        split = _required_text(row, "split", prefix, errors)

        if recording_id:
            if recording_id in recording_ids:
                errors.append(f"{prefix}: recording_id 重复: {recording_id}")
            recording_ids.add(recording_id)

        if split and split not in ALLOWED_SPLITS:
            errors.append(f"{prefix}: split 必须是 {sorted(ALLOWED_SPLITS)} 之一")
        if split in ALLOWED_SPLITS:
            split_rows[split] += 1
            if subject_id:
                subject_splits[subject_id].add(split)
                split_subjects[split].add(subject_id)

        legal_basis = row.get("legal_basis")
        if legal_basis not in ALLOWED_LEGAL_BASES:
            errors.append(f"{prefix}: legal_basis 必须是 {sorted(ALLOWED_LEGAL_BASES)} 之一")
        if row.get("usage_permitted") is not True:
            errors.append(f"{prefix}: usage_permitted 必须明确为 true")

        if split in {"test", "external_test"}:
            label = row.get("label")
            if label not in (0, 1, False, True):
                errors.append(f"{prefix}: 独立测试记录的 label 必须为 0 或 1")
            else:
                test_labels[int(label)] += 1
            _required_text(row, "label_source", prefix, errors)
            for field in AUDIT_FIELDS:
                _required_text(row, field, prefix, errors)

    for subject_id, splits in sorted(subject_splits.items()):
        if len(splits) > 1:
            errors.append(
                f"受试者 {subject_id} 跨集合泄漏: {', '.join(sorted(splits))}"
            )

    if not (split_rows["test"] or split_rows["external_test"]):
        errors.append("至少需要 test 或 external_test 独立测试集合")
    if test_labels[0] == 0 or test_labels[1] == 0:
        errors.append("独立测试集合必须同时包含阳性和阴性参考标签")

    for split in sorted(ALLOWED_SPLITS):
        if split_rows[split] and len(split_subjects[split]) < 20:
            warnings.append(f"{split} 仅包含 {len(split_subjects[split])} 名受试者，统计结果可能不稳定")

    return DatasetValidation(
        passed=not errors,
        rows=len(rows),
        subjects=len(subject_splits),
        split_rows=dict(sorted(split_rows.items())),
        split_subjects={
            split: len(subjects) for split, subjects in sorted(split_subjects.items())
        },
        errors=errors,
        warnings=warnings,
    )


def _required_text(
    row: dict[str, Any], field: str, prefix: str, errors: list[str]
) -> str | None:
    value = row.get(field)
    if not isinstance(value, str) or not value.strip():
        errors.append(f"{prefix}: 缺少非空字段 {field}")
        return None
    return value.strip()


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Validate consent, labels, subgroup metadata and subject-level split isolation"
    )
    parser.add_argument("--input", type=Path, required=True, help="Dataset manifest JSONL")
    args = parser.parse_args()
    result = validate_rows(load_jsonl(args.input))
    print(json.dumps(asdict(result), ensure_ascii=False, indent=2))
    if not result.passed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
