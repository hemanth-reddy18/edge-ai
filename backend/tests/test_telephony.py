import os
import sys
import json
import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from app.main import app, ADMIN_TOKEN
from app.provider import (
    STATE_NOT_CONFIGURED,
    STATE_CREDENTIALS_INVALID,
    STATE_ACCOUNT_NOT_READY,
    STATE_NUMBER_NOT_PROVISIONED,
    STATE_INBOUND_ROUTE_MISSING,
    STATE_GATEWAY_UNREACHABLE,
    STATE_MEDIA_NOT_READY,
    STATE_CONNECTED
)

client = TestClient(app)

def test_telephony_status_endpoint():
    res = client.get("/v1/telephony/status")
    assert res.status_code == 200
    data = res.json()
    assert "configured" in data
    assert "missing_prerequisites" in data

def test_telephony_config_security():
    # Capture original config for non-destructive teardown
    orig_res = client.get("/v1/telephony/config", headers={"Authorization": f"Bearer {ADMIN_TOKEN}"})
    orig_cfg = orig_res.json() if orig_res.status_code == 200 else {}

    try:
        # Unauthorized update attempt
        res = client.post("/v1/telephony/config", json={
            "sip_registrar_host": "sip.provider.com",
            "sip_username": "edge_trunk_1",
            "inbound_did": "+15551234567"
        })
        assert res.status_code == 401

        # Authorized update
        res_auth = client.post(
            "/v1/telephony/config",
            headers={"Authorization": f"Bearer {ADMIN_TOKEN}"},
            json={
                "sip_registrar_host": "sip.telnyx.com",
                "sip_registrar_port": 5060,
                "sip_username": "edge_demo_trunk",
                "sip_password": "super_secret_sip_pass",
                "inbound_did": "+18005550199",
                "trunk_provider": "telnyx"
            }
        )
        assert res_auth.status_code == 200
        assert res_auth.json()["status"] == "configured"

        # Status should now report configured
        status_res = client.get("/v1/telephony/status")
        assert status_res.status_code == 200
        status_data = status_res.json()
        assert status_data["configured"] is True
        assert status_data["inbound_did"] == "+18005550199"
        assert status_data["sip_registrar_host"] == "sip.telnyx.com"
        assert status_data["sip_registered"] is False
    finally:
        # Non-destructive teardown: restore whatever was originally configured
        if orig_cfg and "sip_registrar_host" in orig_cfg:
            client.post(
                "/v1/telephony/config",
                headers={"Authorization": f"Bearer {ADMIN_TOKEN}"},
                json={
                    "sip_registrar_host": orig_cfg.get("sip_registrar_host"),
                    "sip_registrar_port": orig_cfg.get("sip_registrar_port", 5060),
                    "sip_username": orig_cfg.get("sip_username"),
                    "inbound_did": orig_cfg.get("inbound_did"),
                    "trunk_provider": orig_cfg.get("trunk_provider", "local_asterisk_pjsip")
                }
            )

def test_inbound_carrier_call_lifecycle_and_websockets():
    call_id = "pstn_call_test_998877"
    caller_num = "+19876543210"
    called_did = "+18005550199"

    # 1. Carrier Gateway sends inbound call webhook
    res = client.post(
        "/v1/telephony/webhook/inbound-call",
        headers={"Authorization": f"Bearer {ADMIN_TOKEN}"},
        json={
            "call_id": call_id,
            "caller_number": caller_num,
            "called_did": called_did,
            "codec": "pcm_s16le"
        }
    )
    assert res.status_code == 200
    call_info = res.json()
    assert call_info["status"] == "initiated"
    token = call_info["pairing_token"]

    # 2. Connect Carrier Gateway WebSocket and Android Device WebSocket
    with client.websocket_connect(f"/ws/telephony/gateway-media?call_id={call_id}&token={token}") as gw_ws:
        with client.websocket_connect(f"/ws/telephony/carrier-media?call_id={call_id}&token={token}") as dev_ws:
            # Android device receives PEER_CONNECTED
            msg = dev_ws.receive_json()
            assert msg["type"] == "PEER_CONNECTED"
            assert msg["caller_number"] == caller_num

            # Gateway receives PEER_CONNECTED
            gw_msg = gw_ws.receive_json()
            assert gw_msg["type"] == "PEER_CONNECTED"

            # Gateway streams 16kHz PCM audio frames to Android device
            test_pcm = b"\x00\x05" * 1600 # 100ms audio
            gw_ws.send_bytes(test_pcm)
            dev_pcm = dev_ws.receive_bytes()
            assert len(dev_pcm) == len(test_pcm)

            # Android device streams AI synthesized reply PCM back to Gateway
            ai_reply_pcm = b"\x10\x20" * 3200
            dev_ws.send_bytes(ai_reply_pcm)
            gw_recvd = gw_ws.receive_bytes()
            assert len(gw_recvd) == len(ai_reply_pcm)

            # Device sends transcript
            dev_ws.send_text(json.dumps({"type": "TRANSCRIPT", "speaker": "AI", "text": "Welcome to Edge Realty"}))
            transcript_msg = gw_ws.receive_json()
            assert transcript_msg["text"] == "Welcome to Edge Realty"

# --- PROVIDER INTEGRATION & STATE MACHINE TESTS ---

def test_provider_status_initially_not_configured():
    res = client.get("/v1/telephony/provider/status")
    assert res.status_code == 200
    data = res.json()
    assert data["provider"] == "exotel"
    assert "state" in data
    assert "missing_prerequisites" in data
    assert "carrier_forwarding_guidance" in data

def test_provider_config_secret_masking():
    # Update with secret token
    res = client.post(
        "/v1/telephony/provider",
        headers={"Authorization": f"Bearer {ADMIN_TOKEN}"},
        json={
            "provider_name": "exotel",
            "account_sid": "exo_test_account_123",
            "api_key": "key_abcdef123456",
            "api_token": "secret_token_never_leak_9999",
            "subdomain": "mumbai.exotel.com",
            "inbound_did": "+918047192837",
            "gateway_public_uri": "sip:gw.edgereception.ai:5060"
        }
    )
    assert res.status_code == 200
    data = res.json()
    assert data["account_sid"] == "exo_test_account_123"
    assert "secret_token_never_leak_9999" not in str(data)
    assert data["api_token_masked"] is not None
    assert "******" in data["api_token_masked"]

    # Verify GET also masks secret
    get_res = client.get("/v1/telephony/provider", headers={"Authorization": f"Bearer {ADMIN_TOKEN}"})
    assert get_res.status_code == 200
    get_data = get_res.json()
    assert "secret_token_never_leak_9999" not in str(get_data)
    assert "******" in get_data["api_token_masked"]

def test_provider_state_rejection_of_private_ip():
    # Testing that private RFC1918 URI (e.g. 192.168.0.101) transitions to GATEWAY_UNREACHABLE
    res = client.post(
        "/v1/telephony/provider",
        headers={"Authorization": f"Bearer {ADMIN_TOKEN}"},
        json={
            "account_sid": "exo_valid_sid_123",
            "api_key": "valid_api_key",
            "api_token": "valid_api_token_12345",
            "inbound_did": "+918047192837",
            "gateway_public_uri": "sip:192.168.0.101:5060"
        }
    )
    assert res.status_code == 200
    assert res.json()["state"] == STATE_GATEWAY_UNREACHABLE
    assert "private" in res.json()["status_details"].lower() or "rfc1918" in res.json()["status_details"].lower()

def test_provider_state_rejection_of_extension_1000():
    # Testing that shortcode 1000 is rejected as NUMBER_NOT_PROVISIONED
    res = client.post(
        "/v1/telephony/provider",
        headers={"Authorization": f"Bearer {ADMIN_TOKEN}"},
        json={
            "account_sid": "exo_valid_sid_123",
            "api_key": "valid_api_key",
            "api_token": "valid_api_token_12345",
            "inbound_did": "1000",
            "gateway_public_uri": "sip:gw.edgereception.ai:5060"
        }
    )
    assert res.status_code == 200
    assert res.json()["state"] == STATE_NUMBER_NOT_PROVISIONED
    assert "e.164" in res.json()["status_details"].lower()

def test_provider_state_invalid_credentials():
    res = client.post(
        "/v1/telephony/provider",
        headers={"Authorization": f"Bearer {ADMIN_TOKEN}"},
        json={
            "account_sid": "exo_valid_sid_123",
            "api_key": "invalid_key",
            "api_token": "invalid_token",
            "inbound_did": "+918047192837",
            "gateway_public_uri": "sip:gw.edgereception.ai:5060"
        }
    )
    assert res.status_code == 200
    assert res.json()["state"] == STATE_CREDENTIALS_INVALID

def test_provider_state_account_suspended():
    res = client.post(
        "/v1/telephony/provider",
        headers={"Authorization": f"Bearer {ADMIN_TOKEN}"},
        json={
            "account_sid": "suspended_account",
            "api_key": "valid_key",
            "api_token": "valid_token",
            "inbound_did": "+918047192837",
            "gateway_public_uri": "sip:gw.edgereception.ai:5060"
        }
    )
    assert res.status_code == 200
    assert res.json()["state"] == STATE_ACCOUNT_NOT_READY

def test_exotel_inbound_webhook():
    res = client.post(
        "/v1/telephony/webhook/exotel/inbound",
        json={
            "CallSid": "exotel_call_abc123xyz",
            "From": "+919876543210",
            "To": "+918047192837",
            "CallType": "transferred",
            "Direction": "inbound"
        }
    )
    assert res.status_code == 200
    data = res.json()
    assert data["status"] == "initiated"
    assert data["call_id"] == "exo_exotel_call_abc123xyz"
    assert data["caller_number"] == "+919876543210"
    assert data["called_did"] == "+918047192837"
    assert "pairing_token" in data

def test_telephony_diagnostics():
    res = client.get("/v1/telephony/diagnostics")
    assert res.status_code == 200
    diag = res.json()
    assert "local_sip_lab" in diag
    assert "public_provider" in diag
    assert "device_registration" in diag
    assert "carrier_forwarding" in diag
    assert diag["local_sip_lab"]["asterisk_version"] == "Asterisk 23.5.0 (Docker)"
    assert diag["carrier_forwarding"]["ussd_status"] == "SUSPENDED_BY_DOT_APRIL_2024"

def test_business_scripts_api():
    res = client.get("/v1/business-scripts")
    assert res.status_code == 200
    data = res.json()
    assert "version" in data
    assert "templates" in data
    template_ids = [t["id"] for t in data["templates"]]
    assert "REAL_ESTATE" in template_ids
    assert "SUPERMARKET" in template_ids
    assert "MEDICAL_PHARMACY" in template_ids
    assert "CUSTOMER_SUPPORT" in template_ids
    assert "CUSTOM" in template_ids
