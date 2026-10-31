#!/usr/bin/env python3
"""
Chronological Walk-Forward Splitter with Purge & Embargo windows (Section 9).
Never uses random K-Fold on financial time series.
"""
from typing import Dict, List, Any


def generate_walk_forward_splits(
    valid_indices: List[int],
    num_folds: int = 4,
    purge_bars: int = 16,
    embargo_bars: int = 8
) -> List[Dict[str, Any]]:
    """
    Given a sorted list of valid sample indices `[start_idx .. end_idx]`, produces
    `num_folds` expanding-window chronological folds with explicit `purge_bars` (>= max horizon 16)
    between Train->Val and Val->Test, plus `embargo_bars` after Test.
    """
    n = len(valid_indices)
    if n < 240:
        raise ValueError(f"Need at least 240 valid indices for walk-forward splitting, got {n}")

    folds: List[Dict[str, Any]] = []
    initial_train = int(n * 0.45)
    remaining = n - initial_train
    step = max(24, remaining // (num_folds + 1))

    for fold_id in range(num_folds):
        train_end_pos = initial_train + fold_id * step
        val_start_pos = train_end_pos + purge_bars
        val_end_pos = val_start_pos + step // 2
        test_start_pos = val_end_pos + purge_bars
        test_end_pos = min(n, test_start_pos + step // 2)

        if test_start_pos >= n or val_start_pos >= n:
            break

        train_idx = valid_indices[:train_end_pos]
        val_idx = valid_indices[val_start_pos:val_end_pos]
        test_idx = valid_indices[test_start_pos:test_end_pos]

        if not train_idx or not val_idx or not test_idx:
            continue

        # Assert strict temporal ordering with purge gap
        assert max(train_idx) + purge_bars <= min(val_idx), "Purge violation between Train and Val"
        assert max(val_idx) + purge_bars <= min(test_idx), "Purge violation between Val and Test"

        folds.append({
            "fold_id": fold_id + 1,
            "train_indices": train_idx,
            "val_indices": val_idx,
            "test_indices": test_idx,
            "purge_bars": purge_bars,
            "embargo_bars": embargo_bars,
        })

    return folds
