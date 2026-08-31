from __future__ import annotations

import pytest

from app.gpu_runtime import parse_device_index


@pytest.mark.parametrize(
    ("value", "expected"),
    [(None, 0), ("", 0), ("0", 0), (" 1 ", 1), (2, 2)],
)
def test_parse_device_index(value, expected):
    assert parse_device_index(value) == expected


@pytest.mark.parametrize("value", ["gpu0", "-1"])
def test_parse_device_index_rejects_invalid_values(value):
    with pytest.raises(ValueError, match="device index"):
        parse_device_index(value)
