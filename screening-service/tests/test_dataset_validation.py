from evaluation.validate_dataset import validate_rows


def row(recording_id: str, subject_id: str, split: str, label: int) -> dict:
    return {
        "recording_id": recording_id,
        "subject_id": subject_id,
        "split": split,
        "label": label,
        "label_source": "specialist-consensus-v1",
        "legal_basis": "EXPLICIT_CONSENT",
        "usage_permitted": True,
        "language": "普通话",
        "age_group": "70-79",
        "education_group": "中学",
        "sex": "女",
        "device": "mobile-a",
    }


def test_accepts_subject_isolated_manifest_with_auditable_test_labels():
    rows = [
        row("train-1", "S001", "train", 0),
        row("test-1", "S101", "test", 1),
        row("test-2", "S102", "test", 0),
    ]

    result = validate_rows(rows)

    assert result.passed is True
    assert result.subjects == 3
    assert result.split_rows == {"test": 2, "train": 1}


def test_rejects_subject_leakage_across_splits():
    rows = [
        row("train-1", "S001", "train", 1),
        row("test-1", "S001", "test", 1),
        row("test-2", "S002", "test", 0),
    ]

    result = validate_rows(rows)

    assert result.passed is False
    assert any("跨集合泄漏" in error for error in result.errors)


def test_rejects_missing_consent_or_legal_permission_and_audit_fields():
    positive = row("test-1", "S101", "external_test", 1)
    positive["usage_permitted"] = False
    positive["language"] = ""
    negative = row("test-2", "S102", "external_test", 0)

    result = validate_rows([positive, negative])

    assert result.passed is False
    assert any("usage_permitted" in error for error in result.errors)
    assert any("language" in error for error in result.errors)
