from evaluation.evaluate import evaluate_groups, evaluate_rows


def sample_rows():
    return [
        {"label": 1, "score": 0.9, "quality_passed": True, "language": "普通话"},
        {"label": 1, "score": 0.7, "quality_passed": True, "language": "方言"},
        {"label": 0, "score": 0.2, "quality_passed": True, "language": "普通话"},
        {"label": 0, "score": 0.6, "quality_passed": True, "language": "方言"},
        {"label": 1, "score": None, "quality_passed": False, "language": "普通话"},
    ]


def test_computes_screening_and_coverage_metrics():
    metrics = evaluate_rows(sample_rows(), threshold=0.5)

    assert metrics.total == 5
    assert metrics.evaluated == 4
    assert metrics.coverage == 0.8
    assert metrics.sensitivity == 1.0
    assert metrics.specificity == 0.5
    assert metrics.roc_auc == 1.0
    assert metrics.true_positive == 2
    assert metrics.false_positive == 1
    assert metrics.sensitivity_ci_95 is not None
    assert metrics.sensitivity_ci_95[0] < metrics.sensitivity_ci_95[1]


def test_computes_metrics_by_protected_or_operational_group():
    groups = evaluate_groups(sample_rows(), ["language"], threshold=0.5)

    assert groups["language"]["普通话"]["evaluated"] == 2
    assert groups["language"]["方言"]["specificity"] == 0.0


def test_rejects_invalid_probability_range():
    rows = [{"label": 1, "score": 1.1, "quality_passed": True}]

    try:
        evaluate_rows(rows)
        assert False, "expected ValueError"
    except ValueError as exception:
        assert "score" in str(exception)
