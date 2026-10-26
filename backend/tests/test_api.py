import os
import sys
import time
import json
import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from app.main import app, ADMIN_TOKEN, SECRET_KEY, sign_lease, hash_token, get_db

client = TestClient(app)

DEMO_CLIENT_ID = "client_s22_demo"
DEMO_CLIENT_TOKEN = "edge_client_token_demo_2026"

def test_health_check():
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "healthy"
    assert data["security_mode"] == "authenticated_rbac"
    assert "Edge" in data["service"]

def test_admin_authentication_and_client_management():
    # Unauthorized listing
    res_list_unauth = client.get("/v1/clients")
    assert res_list_unauth.status_code == 401

    # Authorized listing
    res_list_auth = client.get("/v1/clients", headers={"Authorization": f"Bearer {ADMIN_TOKEN}"})
    assert res_list_auth.status_code == 200
    assert any(c["id"] == DEMO_CLIENT_ID for c in res_list_auth.json())

    # Unauthorized creation
    res_create_unauth = client.post("/v1/clients", json={
        "id": "unauth_test_client",
        "business_name": "Test Unauth",
        "business_type": "Retail"
    })
    assert res_create_unauth.status_code == 401

    # Authorized creation
    unique_corp_id = f"test_corp_{int(time.time())}"
    res_create_auth = client.post("/v1/clients", headers={"Authorization": f"Bearer {ADMIN_TOKEN}"}, json={
        "id": unique_corp_id,
        "business_name": "Test Corporation",
        "business_type": "Logistics",
        "subscription_days": 15,
        "client_token": "test_corp_secret_token"
    })
    assert res_create_auth.status_code == 200
    assert res_create_auth.json()["client_id"] == unique_corp_id
    assert "client_token" in res_create_auth.json()

def test_client_lease_and_signature_verification():
    response = client.get(f"/v1/clients/{DEMO_CLIENT_ID}/lease")
    assert response.status_code == 200
    data = response.json()
    assert data["client_id"] == DEMO_CLIENT_ID
    assert data["is_active"] is True
    assert "signed_token" in data

    token_parts = data["signed_token"].split(":")
    assert len(token_parts) == 4 # client_id, expiry, kill_switch, signature

    # Test lease verification endpoint
    res_verify = client.post("/v1/leases/verify", json={"signed_token": data["signed_token"]})
    assert res_verify.status_code == 200
    verify_data = res_verify.json()
    assert verify_data["valid"] is True
    assert verify_data["client_id"] == DEMO_CLIENT_ID
    assert verify_data["kill_switch_active"] is False

    # Test tampered lease token
    tampered_token = data["signed_token"][:-4] + "dead"
    res_tampered = client.post("/v1/leases/verify", json={"signed_token": tampered_token})
    assert res_tampered.status_code == 200
    assert res_tampered.json()["valid"] is False

def test_kill_switch_revocation_and_fail_closed():
    # Toggle kill switch ON
    patch_res = client.patch(
        f"/v1/clients/{DEMO_CLIENT_ID}/status",
        headers={"Authorization": f"Bearer {ADMIN_TOKEN}"},
        json={"kill_switch": True}
    )
    assert patch_res.status_code == 200

    # Lease retrieval must now fail-closed
    lease_res = client.get(f"/v1/clients/{DEMO_CLIENT_ID}/lease")
    assert lease_res.status_code == 200
    data = lease_res.json()
    assert data["is_active"] is False
    assert data["kill_switch_active"] is True

    # Telemetry should be rejected while kill switch is active
    telemetry_data = {
        "call_id": "call_killswitch_blocked",
        "client_id": DEMO_CLIENT_ID,
        "duration_seconds": 12,
        "language": "English",
        "outcome": "FAIL",
        "summary": "Should be blocked by active kill-switch"
    }
    telemetry_res = client.post(
        "/v1/telemetry/calls",
        headers={"X-Client-Token": DEMO_CLIENT_TOKEN},
        json=telemetry_data
    )
    assert telemetry_res.status_code == 403

    # Restore kill switch to OFF
    client.patch(
        f"/v1/clients/{DEMO_CLIENT_ID}/status",
        headers={"Authorization": f"Bearer {ADMIN_TOKEN}"},
        json={"kill_switch": False}
    )

def test_server_side_subscription_expiry():
    expiring_id = f"exp_client_{int(time.time())}"
    # Create client with 1 day expiry
    client.post("/v1/clients", headers={"Authorization": f"Bearer {ADMIN_TOKEN}"}, json={
        "id": expiring_id,
        "business_name": "Expiring Services",
        "business_type": "Consulting",
        "subscription_days": 1,
        "client_token": "expiring_token_secret"
    })

    # Manually expire client in DB
    conn = get_db()
    c = conn.cursor()
    c.execute("UPDATE clients SET subscription_expiry = ? WHERE id = ?", (int(time.time()) - 100, expiring_id))
    conn.commit()
    conn.close()

    # Lease retrieval must report expired
    lease_res = client.get(f"/v1/clients/{expiring_id}/lease")
    assert lease_res.status_code == 200
    assert lease_res.json()["is_active"] is False
    assert lease_res.json()["status"] == "expired"

    # Telemetry ingestion must be rejected with 403
    telemetry_res = client.post(
        "/v1/telemetry/calls",
        headers={"X-Client-Token": "expiring_token_secret"},
        json={
            "call_id": f"call_expired_{int(time.time())}",
            "client_id": expiring_id,
            "duration_seconds": 10,
            "language": "English",
            "outcome": "FAIL",
            "summary": "Expired client test"
        }
    )
    assert telemetry_res.status_code == 403
    assert "subscription has expired" in telemetry_res.json()["detail"].lower()

def test_telemetry_authentication_and_idempotency():
    call_id = f"test_call_audit_{int(time.time())}"
    telemetry_data = {
        "call_id": call_id,
        "client_id": DEMO_CLIENT_ID,
        "caller_id": "+91 98765 43210",
        "duration_seconds": 55,
        "language": "Telugu",
        "outcome": "LEAD",
        "summary": "Customer confirmed visit for 3BHK villa tomorrow at 11 AM."
    }

    # 1. Reject without auth credentials
    res_no_auth = client.post("/v1/telemetry/calls", json=telemetry_data)
    assert res_no_auth.status_code == 401

    # 2. Reject with invalid client token
    res_bad_auth = client.post("/v1/telemetry/calls", headers={"X-Client-Token": "wrong_token"}, json=telemetry_data)
    assert res_bad_auth.status_code == 401

    # 3. Accept with valid client token
    res_auth = client.post("/v1/telemetry/calls", headers={"X-Client-Token": DEMO_CLIENT_TOKEN}, json=telemetry_data)
    assert res_auth.status_code == 200
    assert res_auth.json()["status"] == "recorded"
    assert res_auth.json()["call_id"] == call_id

    # 4. Idempotent re-submission (must succeed without creating duplicate)
    res_repeat = client.post("/v1/telemetry/calls", headers={"X-Client-Token": DEMO_CLIENT_TOKEN}, json=telemetry_data)
    assert res_repeat.status_code == 200

    # 5. Verify record and caller ID privacy masking
    calls_res = client.get(f"/v1/clients/{DEMO_CLIENT_ID}/calls", headers={"X-Client-Token": DEMO_CLIENT_TOKEN})
    assert calls_res.status_code == 200
    matching = [c for c in calls_res.json() if c["call_id"] == call_id]
    assert len(matching) == 1
    assert matching[0]["caller_id"] == "...3210"

def test_remote_prompt_configuration():
    # Unauthorized prompt update
    res_unauth = client.put(f"/v1/clients/{DEMO_CLIENT_ID}/prompt", json={
        "version": "v2.0",
        "prompt_text": "Updated bilingual prompt with special focus on Telugu and English."
    })
    assert res_unauth.status_code == 401

    # Authorized prompt update
    res_auth = client.put(
        f"/v1/clients/{DEMO_CLIENT_ID}/prompt",
        headers={"Authorization": f"Bearer {ADMIN_TOKEN}"},
        json={
            "version": "v2.0",
            "prompt_text": "Updated bilingual prompt with special focus on Telugu and English."
        }
    )
    assert res_auth.status_code == 200
    assert res_auth.json()["prompt_version"] == "v2.0"

    # Verify updated prompt version reflects in lease
    lease_res = client.get(f"/v1/clients/{DEMO_CLIENT_ID}/lease")
    assert lease_res.status_code == 200
    assert lease_res.json()["prompt_version"] == "v2.0"

# --- LOCAL VOICE TEST & WEBSOCKET RELAY TESTS ---

def test_voice_test_session_and_dialer():
    # 1. Verify GET /test-dialer serves HTML interface
    res_dialer = client.get("/test-dialer")
    assert res_dialer.status_code == 200
    assert "Edge Local Two-Way Voice Test" in res_dialer.text
    assert "Start Local Voice Test" in res_dialer.text
    assert "Local Mac Test Caller" in res_dialer.text

    # 2. Verify POST /v1/voice-test/session creates valid session & token
    res_session = client.post("/v1/voice-test/session")
    assert res_session.status_code == 200
    data = res_session.json()
    assert "session_id" in data
    assert "pairing_token" in data
    assert data["sample_rate"] == 16000
    assert data["format"] == "pcm_s16le"
    assert "/ws/device-audio" in data["ws_device_url"]
    assert "/ws/test-caller" in data["ws_caller_url"]

    # 3. Verify status endpoint
    res_status = client.get("/v1/voice-test/status")
    assert res_status.status_code == 200
    assert "active_sessions" in res_status.json()

def test_voice_test_websocket_pairing_and_pcm_relay():
    # Create test session
    res = client.post("/v1/voice-test/session")
    sess = res.json()
    s_id = sess["session_id"]
    token = sess["pairing_token"]

    # Reject invalid token
    with pytest.raises(Exception):
        with client.websocket_connect(f"/ws/test-caller?session_id={s_id}&token=invalid_token"):
            pass

    # Connect device socket and browser socket with valid pairing
    with client.websocket_connect(f"/ws/device-audio?session_id={s_id}&token={token}") as device_ws:
        with client.websocket_connect(f"/ws/test-caller?session_id={s_id}&token={token}") as caller_ws:
            # Device receives peer notification
            dev_msg = device_ws.receive_json()
            assert dev_msg["type"] == "PEER_CONNECTED"

            # Caller receives peer notification
            caller_msg = caller_ws.receive_json()
            assert caller_msg["type"] == "PEER_CONNECTED"

            # Browser sends START control message -> Device receives it
            caller_ws.send_text(json.dumps({"type": "START", "session_id": s_id}))
            relayed_start = device_ws.receive_json()
            assert relayed_start["type"] == "START"

            # Browser sends 16kHz PCM audio bytes -> Device receives exact bytes
            caller_pcm = bytes([0x12, 0x34] * 160) # 160 samples (10ms at 16kHz)
            caller_ws.send_bytes(caller_pcm)
            relayed_pcm = device_ws.receive_bytes()
            assert relayed_pcm == caller_pcm

            # Device sends AI synthesized response PCM -> Browser receives exact bytes
            ai_pcm = bytes([0x56, 0x78] * 160)
            device_ws.send_bytes(ai_pcm)
            relayed_ai_pcm = caller_ws.receive_bytes()
            assert relayed_ai_pcm == ai_pcm

def test_test_session_isolation_from_production_metrics():
    # Initial production counts
    conn = get_db()
    c = conn.cursor()
    c.execute("SELECT COUNT(*) FROM calls WHERE is_test_session = 0")
    prod_calls_before = c.fetchone()[0]
    conn.close()

    # Submit test session telemetry
    test_call_id = f"local_voice_test_{int(time.time())}"
    res = client.post(
        "/v1/telemetry/calls",
        headers={"X-Client-Token": DEMO_CLIENT_TOKEN},
        json={
            "call_id": test_call_id,
            "client_id": DEMO_CLIENT_ID,
            "caller_id": "Local Mac Test Caller",
            "duration_seconds": 24,
            "language": "English",
            "outcome": "LEAD",
            "summary": "Local test session verifying 2-way audio loop",
            "is_test_session": True
        }
    )
    assert res.status_code == 200
    assert res.json()["is_test_session"] is True

    # Verify production count is unchanged
    conn = get_db()
    c = conn.cursor()
    c.execute("SELECT COUNT(*) FROM calls WHERE is_test_session = 0")
    prod_calls_after = c.fetchone()[0]
    assert prod_calls_after == prod_calls_before

    # Verify test call is recorded in test partition
    c.execute("SELECT is_test_session, caller_id FROM calls WHERE call_id = ?", (test_call_id,))
    row = c.fetchone()
    assert row is not None
    assert row["is_test_session"] == 1
    conn.close()
