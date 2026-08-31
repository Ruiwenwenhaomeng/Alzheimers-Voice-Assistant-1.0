from __future__ import annotations

import argparse
import json
import math
from collections import defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Iterable


@dataclass(frozen=True)
class Metrics:
    total: int
    evaluated: int
    positive: int
    negative: int
    coverage: float
    coverage_ci_95: tuple[float, float] | None
    sensitivity: float | None
    sensitivity_ci_95: tuple[float, float] | None
    specificity: float | None
    specificity_ci_95: tuple[float, float] | None
    positive_predictive_value: float | None
    positive_predictive_value_ci_95: tuple[float, float] | None
    negative_predictive_value: float | None
    negative_predictive_value_ci_95: tuple[float, float] | None
    accuracy: float | None
    accuracy_ci_95: tuple[float, float] | None
    brier_score: float | None
    roc_auc: float | None
    true_positive: int
    true_negative: int
    false_positive: int
    false_negative: int


def evaluate_rows(rows: list[dict[str, Any]], threshold: float = 0.5) -> Metrics:
    if not 0 <= threshold <= 1:
        raise ValueError("threshold 必须在 0 到 1 之间")
    usable: list[tuple[int, float]] = []
    for row in rows:
        if row.get("quality_passed") is not True or row.get("score") is None:
            continue
        label = row.get("label")
        if label not in (0, 1, False, True):
            raise ValueError("label 必须为 0 或 1")
        score = float(row["score"])
        if not 0 <= score <= 1:
            raise ValueError("score 必须在 0 到 1 之间")
        usable.append((int(label), score))

    total = len(rows)
    evaluated = len(usable)
    positive = sum(label for label, _ in usable)
    negative = evaluated - positive
    tp = sum(label == 1 and score >= threshold for label, score in usable)
    tn = sum(label == 0 and score < threshold for label, score in usable)
    fp = sum(label == 0 and score >= threshold for label, score in usable)
    fn = sum(label == 1 and score < threshold for label, score in usable)

    return Metrics(
        total=total,
        evaluated=evaluated,
        positive=positive,
        negative=negative,
        coverage=_ratio(evaluated, total) or 0.0,
        coverage_ci_95=_wilson_interval(evaluated, total),
        sensitivity=_ratio(tp, tp + fn),
        sensitivity_ci_95=_wilson_interval(tp, tp + fn),
        specificity=_ratio(tn, tn + fp),
        specificity_ci_95=_wilson_interval(tn, tn + fp),
        positive_predictive_value=_ratio(tp, tp + fp),
        positive_predictive_value_ci_95=_wilson_interval(tp, tp + fp),
        negative_predictive_value=_ratio(tn, tn + fn),
        negative_predictive_value_ci_95=_wilson_interval(tn, tn + fn),
        accuracy=_ratio(tp + tn, evaluated),
        accuracy_ci_95=_wilson_interval(tp + tn, evaluated),
        brier_score=(
            sum((score - label) ** 2 for label, score in usable) / evaluated
            if evaluated else None
        ),
        roc_auc=_roc_auc(usable),
        true_positive=tp,
        true_negative=tn,
        false_positive=fp,
        false_negative=fn,
    )


def evaluate_groups(
    rows: list[dict[str, Any]], fields: Iterable[str], threshold: float = 0.5
) -> dict[str, dict[str, dict[str, Any]]]:
    result: dict[str, dict[str, dict[str, Any]]] = {}
    for field in fields:
        groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for row in rows:
            groups[str(row.get(field, "UNKNOWN"))].append(row)
        result[field] = {
            group: asdict(evaluate_rows(group_rows, threshold))
            for group, group_rows in sorted(groups.items())
        }
    return result


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as source:
        for line_number, line in enumerate(source, start=1):
            if not line.strip():
                continue
            value = json.loads(line)
            if not isinstance(value, dict):
                raise ValueError(f"第 {line_number} 行必须是 JSON 对象")
            rows.append(value)
    return rows


def _ratio(numerator: int, denominator: int) -> float | None:
    return numerator / denominator if denominator else None


def _wilson_interval(successes: int, total: int, z: float = 1.959963984540054) -> tuple[float, float] | None:
    if total <= 0:
        return None
    proportion = successes / total
    z_squared = z * z
    denominator = 1 + z_squared / total
    centre = (proportion + z_squared / (2 * total)) / denominator
    margin = z * math.sqrt(
        proportion * (1 - proportion) / total + z_squared / (4 * total * total)
    ) / denominator
    return max(0.0, centre - margin), min(1.0, centre + margin)


def _roc_auc(pairs: list[tuple[int, float]]) -> float | None:
    positives = [score for label, score in pairs if label == 1]
    negatives = [score for label, score in pairs if label == 0]
    if not positives or not negatives:
        return None
    wins = 0.0
    for positive in positives:
        for negative in negatives:
            wins += 1.0 if positive > negative else 0.5 if positive == negative else 0.0
    return wins / (len(positives) * len(negatives))


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate an independent speech-screening result set")
    parser.add_argument("--input", type=Path, required=True, help="JSONL prediction file")
    parser.add_argument("--threshold", type=float, default=0.5)
    parser.add_argument("--group-by", default="language,age_group,education_group,sex,device")
    args = parser.parse_args()
    rows = load_jsonl(args.input)
    group_fields = [field.strip() for field in args.group_by.split(",") if field.strip()]
    output = {
        "threshold": args.threshold,
        "overall": asdict(evaluate_rows(rows, args.threshold)),
        "groups": evaluate_groups(rows, group_fields, args.threshold),
    }
    print(json.dumps(output, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
