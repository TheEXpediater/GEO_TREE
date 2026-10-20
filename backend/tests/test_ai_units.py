"""Unit tests for the AI building blocks, on small synthetic feature arrays (no DINOv2)."""

import numpy as np
import pytest

from app.ai.config import AiSettings
from app.ai.dataset import ClassCount, SplitItem, readiness, split_train_validation
from app.ai.features import BackboneUnavailable, Dinov2FeatureExtractor, preprocess
from app.ai.head import LinearHead, fit_head
from app.ai.images import load_rgb, validate_image
from app.ai.metrics import classification_metrics
from app.errors import ApiError
from tests.conftest import leaf_image


def clusters(n_per_class: int, dims: int = 384, seed: int = 0):
    rng = np.random.default_rng(seed)
    centers = rng.normal(0, 1, (3, dims))
    features, labels = [], []
    for index, label in enumerate(["a", "b", "c"]):
        features.append(centers[index] + rng.normal(0, 0.3, (n_per_class, dims)))
        labels += [label] * n_per_class
    return np.vstack(features).astype(np.float32), labels


def test_head_learns_separable_classes_and_round_trips(tmp_path):
    features, labels = clusters(20)
    head = fit_head(features, labels, ["a", "b", "c"], seed=42)
    probabilities = head.predict_proba(features)
    assert probabilities.shape == (60, 3)
    assert np.allclose(probabilities.sum(axis=1), 1.0)
    assert head.predict(features) == labels

    path = tmp_path / "models" / "model_v1.npz"
    head.save(path)
    loaded = LinearHead.load(path)
    assert loaded.class_ids == ["a", "b", "c"]
    assert np.allclose(loaded.predict_proba(features), probabilities)
    assert not list(path.parent.glob("*.tmp"))


def test_head_is_deterministic_and_handles_two_classes():
    features, labels = clusters(10)
    keep = [i for i, label in enumerate(labels) if label != "c"]
    two = fit_head(features[keep], [labels[i] for i in keep], ["a", "b"], seed=7)
    assert two.weights.shape == (2, 384)
    assert two.predict(features[keep]) == [labels[i] for i in keep]
    again = fit_head(features[keep], [labels[i] for i in keep], ["a", "b"], seed=7)
    assert np.allclose(two.weights, again.weights)


def test_head_requires_every_class_in_training_data():
    features, labels = clusters(5)
    with pytest.raises(ValueError):
        fit_head(features, labels, ["a", "b", "c", "d"], seed=1)
    with pytest.raises(ValueError):
        fit_head(features, labels, ["a"], seed=1)


def test_split_is_stratified_deterministic_and_order_independent():
    items = [SplitItem(f"img{c}{i}", c, f"{c}-hash-{i}") for c in "xyz" for i in range(10)]
    train, validation = split_train_validation(items, 0.2, seed=42)
    assert len(train) == 24 and len(validation) == 6
    assert sorted(i.class_id for i in validation) == ["x", "x", "y", "y", "z", "z"]
    shuffled = list(reversed(items))
    assert split_train_validation(shuffled, 0.2, seed=42)[1] == validation or sorted(
        v.image_id for v in split_train_validation(shuffled, 0.2, seed=42)[1]
    ) == sorted(v.image_id for v in validation)
    assert {v.image_id for v in split_train_validation(items, 0.2, seed=43)[1]} != {v.image_id for v in validation}


def test_split_never_separates_identical_content():
    items = [SplitItem(f"i{i}", "x", "same" if i < 3 else f"h{i}") for i in range(6)]
    train, validation = split_train_validation(items, 0.5, seed=1)
    sides = {("train" if item in train else "validation") for item in items if item.sha256 == "same"}
    assert len(sides) == 1


def test_split_keeps_a_split_group_together_and_ignores_it_when_absent():
    # Repeated shots of one leaf (same group, different bytes) never straddle train/validation.
    items = [SplitItem(f"i{c}{n}", c, f"{c}-{n}", f"leaf-{c}-{n // 3}") for c in "xy" for n in range(12)]
    for seed in range(5):
        train, validation = split_train_validation(items, 0.25, seed=seed)
        for group in {item.group for item in items}:
            assert len({("t" if item in train else "v") for item in items if item.group == group}) == 1
        assert {item.class_id for item in validation} == {"x", "y"}
    ungrouped = [SplitItem(i.image_id, i.class_id, i.sha256) for i in items]
    assert split_train_validation(ungrouped, 0.25, seed=3)[1] == split_train_validation(
        [SplitItem(i.image_id, i.class_id, i.sha256, None) for i in items], 0.25, seed=3)[1]


def test_head_with_groups_never_splits_a_group_across_cv_folds_and_still_learns():
    features, labels = clusters(12)
    groups = [f"{label}-{i // 3}" for i, label in enumerate(labels)]
    head = fit_head(features, labels, ["a", "b", "c"], seed=42, groups=groups)
    assert head.predict(features) == labels
    assert set(head.cv_scores) == {"0.01", "0.1", "1.0", "10.0"}
    # Without groups the result is exactly the previous behaviour.
    plain = fit_head(features, labels, ["a", "b", "c"], seed=42)
    assert np.allclose(plain.weights, fit_head(features, labels, ["a", "b", "c"], seed=42, groups=None).weights)


def test_split_keeps_one_image_on_each_side_for_tiny_classes():
    items = [SplitItem("a", "x", "1"), SplitItem("b", "x", "2")]
    train, validation = split_train_validation(items, 0.5, seed=0)
    assert len(train) == 1 and len(validation) == 1
    with pytest.raises(ValueError):
        split_train_validation([SplitItem("a", "x", "1")], 0.5, seed=0)


def test_readiness_explains_every_problem():
    result = readiness([ClassCount("1", "Healthy", 12), ClassCount("2", "Mold", 9)], 10)
    assert result.can_train is False
    assert result.problems == ["Mold needs 1 more image (9 of 10)."]
    assert readiness([ClassCount("1", "Healthy", 12)], 10).problems == ["Add at least two classes."]
    assert readiness([ClassCount("1", "A", 10), ClassCount("2", "B", 10)], 10).can_train is True


def test_metrics_match_hand_computed_values():
    expected = ["h", "h", "h", "s", "s", "m"]
    predicted = ["h", "h", "s", "s", "m", "m"]
    metrics = classification_metrics(expected, predicted, labels=["h", "s", "m"])
    assert metrics["accuracy"] == pytest.approx(4 / 6)
    per = {row["label"]: row for row in metrics["perClass"]}
    assert per["h"]["precision"] == 1.0 and per["h"]["recall"] == pytest.approx(2 / 3)
    assert per["s"]["precision"] == 0.5 and per["s"]["recall"] == 0.5
    assert per["m"]["precision"] == 0.5 and per["m"]["recall"] == 1.0
    assert metrics["macroPrecision"] == pytest.approx((1 + 0.5 + 0.5) / 3)
    assert metrics["macroRecall"] == pytest.approx((2 / 3 + 0.5 + 1) / 3)
    assert metrics["macroF1"] == pytest.approx((0.8 + 0.5 + 2 / 3) / 3)
    assert metrics["confusionMatrix"] == {"labels": ["h", "s", "m"], "matrix": [[2, 1, 0], [0, 1, 1], [0, 0, 1]]}


def test_metrics_macro_ignores_labels_without_expected_samples():
    metrics = classification_metrics(["h", "h"], ["h", "x"])
    assert metrics["confusionMatrix"]["labels"] == ["h", "x"]
    assert metrics["macroRecall"] == 0.5  # only "h" has expected samples
    assert classification_metrics([], [])["accuracy"] == 0.0


def test_preprocess_matches_dinov2_input_contract():
    image = load_rgb(leaf_image((200, 100, 50), 1, size=500))
    array = preprocess(image, 336)
    assert array.shape == (3, 336, 336) and array.dtype == np.float32
    assert abs(float(array[0].mean()) - (200 / 255 - 0.485) / 0.229) < 0.2


def test_extractor_cache_key_tracks_backbone_and_preprocessing():
    small = Dinov2FeatureExtractor(AiSettings(image_size=336))
    larger = Dinov2FeatureExtractor(AiSettings(image_size=448))
    assert small.dimension == 384 and small.display_name == "DINOv2 ViT-S/14"
    assert small.cache_key.startswith("dinov2_vits14|336px")
    assert small.cache_key != larger.cache_key


def test_extractor_reports_a_clear_error_when_the_runtime_is_missing(tmp_path, monkeypatch):
    import builtins

    real_import = builtins.__import__

    def no_torch(name, *args, **kwargs):
        if name == "torch" or name.startswith("torch."):
            raise ImportError("no torch here")
        return real_import(name, *args, **kwargs)

    monkeypatch.setattr(builtins, "__import__", no_torch)
    extractor = Dinov2FeatureExtractor(AiSettings(data_dir=tmp_path))
    with pytest.raises(BackboneUnavailable, match="PyTorch is not installed"):
        extractor.extract([leaf_image((1, 2, 3), 1)])
    assert extractor.status()["loaded"] is False


def test_ai_settings_validation_rejects_unsupported_choices():
    with pytest.raises(RuntimeError):
        AiSettings(model_name="dinov2_vitg14").validate()
    with pytest.raises(RuntimeError):
        AiSettings(image_size=330).validate()
    with pytest.raises(RuntimeError):
        AiSettings(device="tpu").validate()
    assert AiSettings().validate().embedding_dimension == 384


def test_image_validation_by_content():
    png = leaf_image((10, 200, 10), 1)
    validated = validate_image(png, 10_000_000)
    assert validated.extension == "png" and validated.width == 48
    with pytest.raises(ApiError) as too_big:
        validate_image(png, 10)
    assert too_big.value.code == "IMAGE_TOO_LARGE"
    with pytest.raises(ApiError) as fake:
        validate_image(b"\xff\xd8\xff" + b"garbage", 10_000)  # JPEG magic, not a JPEG
    assert fake.value.code == "UNSUPPORTED_IMAGE"
    with pytest.raises(ApiError) as empty:
        validate_image(b"", 10)
    assert empty.value.code == "EMPTY_IMAGE"
