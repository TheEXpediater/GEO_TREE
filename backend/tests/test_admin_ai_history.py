"""Model version history, training jobs and the audit trail of classifier changes."""

from tests.test_ai_admin import API, fill_dataset, run_jobs


def test_model_versions_jobs_and_classifier_audit(ai_client, admin_headers):
    classes = fill_dataset(ai_client, admin_headers)
    assert run_jobs(ai_client) == 1
    fill_dataset(ai_client, admin_headers, per_class=1, seed_offset=50)
    assert ai_client.post(f"{API}/ai/retrain", headers=admin_headers).status_code == 202
    assert run_jobs(ai_client) == 1

    versions = ai_client.get(f"{API}/ai/models", headers=admin_headers).json()["items"]
    assert [v["modelVersion"] for v in versions] == [2, 1]
    assert [v["active"] for v in versions] == [True, False]  # the old version stays listed
    assert versions[0]["metrics"]["confusionMatrix"]["labels"]
    jobs = ai_client.get(f"{API}/ai/jobs", headers=admin_headers).json()["items"]
    assert {j["status"] for j in jobs} == {"COMPLETE"} and len(jobs) == 2

    ai_client.patch(f"{API}/classifiers/{classes['Powdery Mildew']['id']}", json={"name": "Sooty Mold"}, headers=admin_headers)
    actions = [e["action"] for e in ai_client.get(f"{API}/activity?pageSize=50", headers=admin_headers).json()["items"]]
    for expected in ("classifier.images_upload", "model.activated", "model.retrain_requested", "classifier.rename"):
        assert expected in actions, expected

    dashboard = ai_client.get(f"{API}/dashboard", headers=admin_headers).json()
    assert dashboard["model"]["aiEnabled"] is True and dashboard["model"]["activeVersion"] == 2
    assert dashboard["images"]["training"]["total"] == 15
    assert {c["name"] for c in dashboard["images"]["training"]["byClass"]} == {"Healthy", "Spotting", "Sooty Mold"}


def test_field_tester_cannot_read_model_history(ai_client, db):
    from tests.test_admin_roles import add_user, headers_for

    add_user(db, "tester@example.com")
    headers = headers_for(ai_client, "tester@example.com")
    for route in ("/ai/models", "/ai/jobs", "/ai/model", "/classifiers"):
        assert ai_client.get(f"{API}{route}", headers=headers).status_code == 403
