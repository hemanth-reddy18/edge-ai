import os
import sys
import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from app.main import app, ADMIN_TOKEN, SECRET_KEY, sign_lease

client = TestClient(app)

def test_health_check():
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "healthy"
    assert "Edge" in data["service"]

def test_admin_authentication():
    # Unauthorized attempt
    res_unauth = client.post("/v1/clients", json={
        "id": "unauth_client",
        "business_name": "Test",
        "business_type": "Retail"
    })
    assert res_unauth.status_code == 401

    # Authorized login
    res_login = client.post("/v1/auth/login", json={"token": ADMIN_TOKEN})
    assert res_login.status_code == 200
    assert "access_token" in res_login.json()

def test_get_client_lease_and_signature():
    response = client.get("/v1/clients/client_s22_demo/lease")
    assert response.status_code == 200
    data = response.json()
    assert data["client_id"] == "client_s22_demo"
    assert data["is_active"] is True
    assert "signed_token" in data

    token_parts = data["signed_token"].split(":")
    assert len(token_parts) == 4 # client_id, expiry, kill_switch, signature

def test_kill_switch_revocation():
    # Toggle kill switch ON
    patch_res = client.patch(
        "/v1/clients/client_s22_demo/status",
        headers={"Authorization": f"Bearer {ADMIN_TOKEN}"},
        json={"kill_switch": True}
    )
    assert patch_res.status_code == 200

    # Lease must now fail-closed
    lease_res = client.get("/v1/clients/client_s22_demo/lease")
    assert lease_res.status_code == 200
    data = lease_res.json()
    assert data["is_active"] is False
    assert data["kill_switch_active"] is True

    # Restore kill switch to OFF
    client.patch(
        "/v1/clients/client_s22_demo/status",
        headers={"Authorization": f"Bearer {ADMIN_TOKEN}"},
        json={"kill_switch": False}
    )

def test_record_telemetry_idempotent():
    telemetry_data = {
        "call_id": "test_call_999",
        "client_id": "client_s22_demo",
        "caller_id": "+91 99999 88888",
        "duration_seconds": 45,
        "language": "Telugu",
        "outcome": "LEAD",
        "summary": "Customer requested 3BHK flat in Gachibowli with 2 parking slots."
    }
    res1 = client.post("/v1/telemetry/calls", json=telemetry_data)
    assert res1.status_code == 200
    assert res1.json()["call_id"] == "test_call_999"

    # Idempotent re-send
    res2 = client.post("/v1/telemetry/calls", json=telemetry_data)
    assert res2.status_code == 200

def test_support_report_submission():
    report_data = {
        "client_id": "client_s22_demo",
        "call_id": "test_call_999",
        "description": "Customer voice was slightly muffled, but transcription succeeded."
    }
    res = client.post("/v1/support/reports", json=report_data)
    assert res.status_code == 200
    assert res.json()["status"] == "submitted"
