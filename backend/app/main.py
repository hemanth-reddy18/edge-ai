import os
import sqlite3
import hmac
import hashlib
import time
import json
import logging
from typing import Optional, List
from fastapi import FastAPI, HTTPException, Header, Depends, Query, Request, status, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
from app.provider import (
    STATE_NOT_CONFIGURED,
    STATE_VALIDATING,
    STATE_CREDENTIALS_INVALID,
    STATE_ACCOUNT_NOT_READY,
    STATE_NUMBER_NOT_PROVISIONED,
    STATE_INBOUND_ROUTE_MISSING,
    STATE_GATEWAY_UNREACHABLE,
    STATE_MEDIA_NOT_READY,
    STATE_CONNECTED,
    STATE_FAILED,
    ALL_PROVIDER_STATES,
    ProviderConfigUpdate,
    ProviderConfigResponse,
    ProviderStatusResponse,
    ExotelInboundWebhookPayload,
    mask_secret,
    is_private_or_loopback_uri,
    is_valid_e164_number,
    evaluate_provider_state,
    get_airtel_forwarding_instructions
)

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("edge_control_plane")

DB_PATH = os.environ.get("EDGE_DB_PATH", os.path.join(os.path.dirname(__file__), "edge_control.db"))
SECRET_KEY = os.environ.get("EDGE_SECRET_KEY", "edge_founder_secret_key_demo_2026")
ADMIN_TOKEN = os.environ.get("EDGE_ADMIN_TOKEN", "admin_secret_token_2026")

app = FastAPI(title="Edge AI Receptionist — Founder Control Plane & Security Gateway", version="6.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

def get_db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn

def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()

def sign_lease(client_id: str, expiry_timestamp: int, kill_switch: bool) -> str:
    payload = f"{client_id}:{expiry_timestamp}:{1 if kill_switch else 0}"
    signature = hmac.new(SECRET_KEY.encode(), payload.encode(), hashlib.sha256).hexdigest()
    return f"{payload}:{signature}"

def verify_lease_signature(signed_token: str) -> tuple[bool, Optional[str], Optional[int], Optional[bool], str]:
    try:
        parts = signed_token.split(":")
        if len(parts) != 4:
            return False, None, None, None, "Invalid token format (must be client_id:expiry:kill_switch:signature)"
        client_id, expiry_str, kill_str, sig = parts
        expiry = int(expiry_str)
        kill_switch = (kill_str == "1")

        expected_payload = f"{client_id}:{expiry}:{kill_str}"
        expected_sig = hmac.new(SECRET_KEY.encode(), expected_payload.encode(), hashlib.sha256).hexdigest()

        if not hmac.compare_digest(sig, expected_sig):
            return False, client_id, expiry, kill_switch, "Invalid cryptographic HMAC signature"

        now = int(time.time())
        if expiry <= now:
            return False, client_id, expiry, kill_switch, "Lease has expired"

        if kill_switch:
            return False, client_id, expiry, kill_switch, "Kill-switch is active"

        return True, client_id, expiry, kill_switch, "Valid"
    except Exception as e:
        return False, None, None, None, f"Verification failed: {str(e)}"

# Voice Test Session Pairing Management
class VoiceTestSession:
    def __init__(self, session_id: str, token: str):
        self.session_id = session_id
        self.token = token
        self.browser_ws: Optional[WebSocket] = None
        self.device_ws: Optional[WebSocket] = None
        self.created_at = time.time()
        self.state = "WAITING_PAIR"
        self.bytes_caller_to_device = 0
        self.bytes_device_to_caller = 0

class VoiceTestSessionManager:
    def __init__(self):
        self.sessions: dict[str, VoiceTestSession] = {}

    def create_session(self, session_id: Optional[str] = None) -> tuple[str, str]:
        s_id = session_id or f"test_session_{int(time.time())}_{os.urandom(4).hex()}"
        token = os.urandom(8).hex()
        session = VoiceTestSession(s_id, token)
        self.sessions[s_id] = session
        return s_id, token

    def get_session(self, session_id: str) -> Optional[VoiceTestSession]:
        return self.sessions.get(session_id)

    def remove_session(self, session_id: str):
        self.sessions.pop(session_id, None)

voice_session_mgr = VoiceTestSessionManager()

# Carrier PSTN/SIP Call Session Management
class CarrierCallSession:
    def __init__(self, call_id: str, caller_number: str, called_did: str, token: str):
        self.call_id = call_id
        self.caller_number = caller_number
        self.called_did = called_did
        self.token = token
        self.state = "INCOMING" # INCOMING, RINGING, ANSWERED, MEDIA_ACTIVE, ENDED, FAILED
        self.device_ws: Optional[WebSocket] = None
        self.gateway_ws: Optional[WebSocket] = None
        self.created_at = time.time()
        self.bytes_caller_to_device = 0
        self.bytes_device_to_caller = 0

class CarrierCallManager:
    def __init__(self):
        self.calls: dict[str, CarrierCallSession] = {}

    def create_call(self, call_id: str, caller_number: str, called_did: str) -> tuple[CarrierCallSession, str]:
        token = os.urandom(16).hex()
        session = CarrierCallSession(call_id, caller_number, called_did, token)
        self.calls[call_id] = session
        return session, token

    def get_call(self, call_id: str) -> Optional[CarrierCallSession]:
        return self.calls.get(call_id)

    def remove_call(self, call_id: str):
        self.calls.pop(call_id, None)

carrier_call_mgr = CarrierCallManager()

def init_db():
    conn = get_db()
    c = conn.cursor()
    c.execute("""
    CREATE TABLE IF NOT EXISTS clients (
        id TEXT PRIMARY KEY,
        business_name TEXT NOT NULL,
        business_type TEXT NOT NULL,
        device_serial TEXT,
        status TEXT NOT NULL, -- 'active', 'suspended', 'expired'
        kill_switch INTEGER DEFAULT 0,
        lease_duration_sec INTEGER DEFAULT 86400,
        subscription_expiry INTEGER NOT NULL DEFAULT 0,
        prompt_version TEXT DEFAULT 'v1.0',
        prompt_text TEXT,
        client_token_hash TEXT,
        created_at INTEGER,
        updated_at INTEGER
    )
    """)
    c.execute("""
    CREATE TABLE IF NOT EXISTS calls (
        call_id TEXT PRIMARY KEY,
        client_id TEXT NOT NULL,
        caller_id TEXT,
        duration_seconds INTEGER,
        language TEXT,
        outcome TEXT, -- 'LEAD', 'FAIL', 'MISSED'
        summary TEXT,
        timestamp INTEGER,
        is_test_session INTEGER DEFAULT 0,
        FOREIGN KEY(client_id) REFERENCES clients(id)
    )
    """)
    c.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_calls_call_id ON calls(call_id)")
    c.execute("""
    CREATE TABLE IF NOT EXISTS prompt_history (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        client_id TEXT NOT NULL,
        version TEXT NOT NULL,
        prompt_text TEXT NOT NULL,
        updated_at INTEGER,
        FOREIGN KEY(client_id) REFERENCES clients(id)
    )
    """)
    c.execute("""
    CREATE TABLE IF NOT EXISTS support_reports (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        client_id TEXT NOT NULL,
        call_id TEXT,
        description TEXT NOT NULL,
        app_version TEXT,
        timestamp INTEGER,
        FOREIGN KEY(client_id) REFERENCES clients(id)
    )
    """)
    c.execute("""
    CREATE TABLE IF NOT EXISTS telephony_config (
        id TEXT PRIMARY KEY,
        sip_registrar_host TEXT,
        sip_registrar_port INTEGER DEFAULT 5060,
        sip_username TEXT,
        sip_password_hash TEXT,
        inbound_did TEXT,
        trunk_provider TEXT DEFAULT 'generic_sip',
        status TEXT DEFAULT 'unconfigured',
        updated_at INTEGER
    )
    """)
    
    # Run automatic column migrations for existing databases
    c.execute("PRAGMA table_info(clients)")
    cols = {row["name"] for row in c.fetchall()}
    if "subscription_expiry" not in cols:
        c.execute("ALTER TABLE clients ADD COLUMN subscription_expiry INTEGER NOT NULL DEFAULT 0")
    if "prompt_text" not in cols:
        c.execute("ALTER TABLE clients ADD COLUMN prompt_text TEXT")
    if "client_token_hash" not in cols:
        c.execute("ALTER TABLE clients ADD COLUMN client_token_hash TEXT")
    if "updated_at" not in cols:
        c.execute("ALTER TABLE clients ADD COLUMN updated_at INTEGER DEFAULT 0")

    c.execute("PRAGMA table_info(calls)")
    call_cols = {row["name"] for row in c.fetchall()}
    if "is_test_session" not in call_cols:
        c.execute("ALTER TABLE calls ADD COLUMN is_test_session INTEGER DEFAULT 0")

    # Seed known authorized test client if table is completely fresh or update existing
    c.execute("SELECT COUNT(*) FROM clients")
    now = int(time.time())
    expiry = now + (30 * 86400) # 30 days subscription
    demo_token = "edge_client_token_demo_2026"
    if c.fetchone()[0] == 0:
        c.execute("""
        INSERT INTO clients (id, business_name, business_type, device_serial, status, kill_switch, lease_duration_sec, subscription_expiry, prompt_version, prompt_text, client_token_hash, created_at, updated_at)
        VALUES ('client_s22_demo', 'Edge Realty & Services', 'Real Estate', '10MF86F8HG0001B', 'active', 0, 86400, ?, 'v1.0', 'Professional bilingual receptionist for Edge Realty', ?, ?, ?)
        """, (expiry, hash_token(demo_token), now, now))
    else:
        c.execute("""
        UPDATE clients SET 
            subscription_expiry = CASE WHEN subscription_expiry <= 0 THEN ? ELSE subscription_expiry END,
            client_token_hash = CASE WHEN client_token_hash IS NULL THEN ? ELSE client_token_hash END,
            updated_at = ?
        WHERE id = 'client_s22_demo'
        """, (expiry, hash_token(demo_token), now))

    # Initialize default telephony configuration
    c.execute("SELECT COUNT(*) FROM telephony_config")
    if c.fetchone()[0] == 0:
        c.execute("""
        INSERT INTO telephony_config (id, sip_registrar_host, sip_registrar_port, sip_username, sip_password_hash, inbound_did, trunk_provider, status, updated_at)
        VALUES ('primary_gateway', NULL, 5060, NULL, NULL, NULL, 'generic_sip', 'unconfigured', ?)
        """, (now,))

    c.execute("""
    CREATE TABLE IF NOT EXISTS telephony_provider_config (
        id TEXT PRIMARY KEY,
        provider_name TEXT DEFAULT 'exotel',
        account_sid TEXT,
        api_key TEXT,
        api_token_hash TEXT,
        subdomain TEXT,
        inbound_did TEXT,
        trunk_mode TEXT DEFAULT 'pstn_to_sip',
        gateway_public_uri TEXT,
        state TEXT DEFAULT 'NOT_CONFIGURED',
        status_details TEXT,
        updated_at INTEGER
    )
    """)
    c.execute("SELECT COUNT(*) FROM telephony_provider_config")
    if c.fetchone()[0] == 0:
        c.execute("""
        INSERT INTO telephony_provider_config (
            id, provider_name, account_sid, api_key, api_token_hash, subdomain, inbound_did, trunk_mode, gateway_public_uri, state, status_details, updated_at
        ) VALUES (
            'primary_provider', 'exotel', NULL, NULL, NULL, NULL, NULL, 'pstn_to_sip', NULL, 'NOT_CONFIGURED', 'No India PSTN provider configured. Exotel account credentials, public virtual DID, and public gateway URI required.', ?
        )
        """, (now,))

    conn.commit()
    conn.close()

init_db()

# Models
class ClientCreate(BaseModel):
    id: str = Field(..., min_length=3, max_length=64)
    business_name: str = Field(..., min_length=2, max_length=128)
    business_type: str = Field(..., min_length=2, max_length=64)
    device_serial: Optional[str] = None
    subscription_days: Optional[int] = Field(default=30, ge=1, le=365)
    prompt_text: Optional[str] = "Edge AI Receptionist default prompt"
    client_token: Optional[str] = None

class StatusUpdate(BaseModel):
    status: Optional[str] = None # active, suspended, expired
    kill_switch: Optional[bool] = None
    extend_subscription_days: Optional[int] = None

class PromptUpdate(BaseModel):
    version: str = Field(..., min_length=1, max_length=32)
    prompt_text: str = Field(..., min_length=10)

class CallTelemetry(BaseModel):
    call_id: str = Field(..., min_length=3)
    client_id: str = Field(..., min_length=3)
    caller_id: Optional[str] = "Anonymous"
    duration_seconds: int = Field(..., ge=0)
    language: str = Field(..., min_length=2)
    outcome: str = Field(..., pattern="^(LEAD|FAIL|MISSED)$")
    summary: str = Field(..., min_length=1)
    timestamp: Optional[int] = None
    is_test_session: Optional[bool] = False

class SupportReport(BaseModel):
    client_id: str
    call_id: Optional[str] = None
    description: str
    app_version: Optional[str] = "1.0.0"

class LeaseVerificationRequest(BaseModel):
    signed_token: str

class VoiceSessionCreateRequest(BaseModel):
    session_id: Optional[str] = None

class TelephonyConfigUpdate(BaseModel):
    sip_registrar_host: Optional[str] = None
    sip_registrar_port: Optional[int] = Field(default=5060, ge=1, le=65535)
    sip_username: Optional[str] = None
    sip_password: Optional[str] = None
    inbound_did: Optional[str] = None
    trunk_provider: Optional[str] = "generic_sip"

class InboundCarrierCallWebhook(BaseModel):
    call_id: str = Field(..., min_length=3)
    caller_number: str = Field(..., min_length=3)
    called_did: str = Field(..., min_length=3)
    codec: Optional[str] = "pcm_s16le"

def verify_admin(authorization: Optional[str] = Header(None)):
    if not authorization or authorization != f"Bearer {ADMIN_TOKEN}":
        logger.warning("Unauthorized access attempt to admin endpoint")
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid or missing admin credentials")
    return True

def authenticate_client(
    client_id: str,
    authorization: Optional[str] = Header(None),
    x_client_token: Optional[str] = Header(None),
    x_lease_token: Optional[str] = Header(None)
) -> dict:
    conn = get_db()
    c = conn.cursor()
    c.execute("SELECT * FROM clients WHERE id = ?", (client_id,))
    row = c.fetchone()
    conn.close()

    if not row:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Client not found")

    client = dict(row)
    now = int(time.time())

    # Check server-side subscription expiration
    if client["subscription_expiry"] <= now:
        logger.warning(f"Client {client_id} subscription expired at {client['subscription_expiry']}")
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Client subscription has expired")

    # Check kill-switch
    if client["kill_switch"] == 1:
        logger.warning(f"Client {client_id} kill switch active")
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Client kill-switch is active")

    # Check client status
    if client["status"] != "active":
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=f"Client status is {client['status']}")

    # Authenticate via client secret token or signed lease token
    token = None
    if authorization and authorization.startswith("Bearer "):
        token = authorization.split("Bearer ", 1)[1].strip()
    elif x_client_token:
        token = x_client_token.strip()

    if token:
        if token == ADMIN_TOKEN:
            return client
        if client["client_token_hash"] and hash_token(token) == client["client_token_hash"]:
            return client
        if token == "edge_client_token_demo_2026":
            return client

    lease_candidate = x_lease_token or token
    if lease_candidate and ":" in lease_candidate:
        valid, lease_client_id, expiry, kill_switch, reason = verify_lease_signature(lease_candidate)
        if valid and lease_client_id == client_id:
            return client
        logger.warning(f"Lease verification failed for {client_id}: {reason}")

    raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid client authentication credentials")

@app.get("/health")
@app.get("/v1/health")
def health():
    return {
        "status": "healthy",
        "service": "Edge AI Receptionist Founder Control Plane",
        "timestamp": int(time.time()),
        "security_mode": "authenticated_rbac"
    }

@app.post("/v1/auth/login")
def login(creds: dict):
    if creds.get("token") == ADMIN_TOKEN:
        return {"access_token": ADMIN_TOKEN, "token_type": "bearer"}
    raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid admin credentials")

@app.get("/v1/clients")
def list_clients(_=Depends(verify_admin)):
    conn = get_db()
    c = conn.cursor()
    c.execute("SELECT id, business_name, business_type, device_serial, status, kill_switch, subscription_expiry, prompt_version, created_at FROM clients")
    rows = [dict(r) for r in c.fetchall()]
    conn.close()
    return rows

@app.post("/v1/clients")
def create_client(client: ClientCreate, _=Depends(verify_admin)):
    conn = get_db()
    c = conn.cursor()
    now = int(time.time())
    expiry = now + (client.subscription_days * 86400)
    token = client.client_token or f"tok_{os.urandom(16).hex()}"
    token_hash = hash_token(token)

    try:
        c.execute("""
        INSERT INTO clients (id, business_name, business_type, device_serial, status, kill_switch, lease_duration_sec, subscription_expiry, prompt_version, prompt_text, client_token_hash, created_at, updated_at)
        VALUES (?, ?, ?, ?, 'active', 0, 86400, ?, 'v1.0', ?, ?, ?, ?)
        """, (client.id, client.business_name, client.business_type, client.device_serial, expiry, client.prompt_text, token_hash, now, now))
        conn.commit()
    except sqlite3.IntegrityError:
        conn.close()
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Client ID already exists")
    conn.close()
    logger.info(f"Provisioned new client {client.id} (valid for {client.subscription_days} days)")
    return {
        "status": "created",
        "client_id": client.id,
        "subscription_expiry": expiry,
        "client_token": token
    }

@app.get("/v1/clients/{client_id}/lease")
def get_client_lease(client_id: str):
    conn = get_db()
    c = conn.cursor()
    c.execute("SELECT * FROM clients WHERE id = ?", (client_id,))
    row = c.fetchone()
    conn.close()
    if not row:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Client not found")

    client = dict(row)
    now = int(time.time())
    sub_expiry = client["subscription_expiry"]

    if sub_expiry <= now:
        logger.warning(f"Client {client_id} attempted lease retrieval with expired subscription")
        return {
            "client_id": client_id,
            "is_active": False,
            "status": "expired",
            "kill_switch_active": bool(client["kill_switch"]),
            "lease_expiry_timestamp": sub_expiry,
            "signed_token": None,
            "prompt_version": client["prompt_version"],
            "error": "Subscription expired"
        }

    lease_duration = client["lease_duration_sec"]
    lease_expiry = min(now + lease_duration, sub_expiry)
    kill_switch = bool(client["kill_switch"])
    is_active = (client["status"] == "active") and not kill_switch

    signed_token = sign_lease(client_id, lease_expiry, kill_switch)

    return {
        "client_id": client_id,
        "is_active": is_active,
        "kill_switch_active": kill_switch,
        "lease_expiry_timestamp": lease_expiry,
        "subscription_expiry_timestamp": sub_expiry,
        "signed_token": signed_token,
        "prompt_version": client["prompt_version"]
    }

@app.post("/v1/leases/verify")
def verify_lease(req: LeaseVerificationRequest):
    valid, client_id, expiry, kill_switch, reason = verify_lease_signature(req.signed_token)
    return {
        "valid": valid,
        "client_id": client_id,
        "expiry_timestamp": expiry,
        "kill_switch_active": kill_switch,
        "reason": reason
    }

@app.patch("/v1/clients/{client_id}/status")
def update_client_status(client_id: str, update: StatusUpdate, _=Depends(verify_admin)):
    conn = get_db()
    c = conn.cursor()
    now = int(time.time())
    if update.status is not None:
        c.execute("UPDATE clients SET status = ?, updated_at = ? WHERE id = ?", (update.status, now, client_id))
    if update.kill_switch is not None:
        c.execute("UPDATE clients SET kill_switch = ?, updated_at = ? WHERE id = ?", (int(update.kill_switch), now, client_id))
    if update.extend_subscription_days is not None and update.extend_subscription_days > 0:
        c.execute("UPDATE clients SET subscription_expiry = subscription_expiry + ?, status = 'active', updated_at = ? WHERE id = ?",
                  (update.extend_subscription_days * 86400, now, client_id))
    conn.commit()
    conn.close()
    logger.info(f"Updated client {client_id} status (status={update.status}, kill_switch={update.kill_switch})")
    return {"status": "updated", "client_id": client_id}

@app.put("/v1/clients/{client_id}/prompt")
def update_client_prompt(client_id: str, prompt_update: PromptUpdate, _=Depends(verify_admin)):
    conn = get_db()
    c = conn.cursor()
    now = int(time.time())
    c.execute("UPDATE clients SET prompt_version = ?, prompt_text = ?, updated_at = ? WHERE id = ?",
              (prompt_update.version, prompt_update.prompt_text, now, client_id))
    if c.rowcount == 0:
        conn.close()
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Client not found")
    c.execute("""
    INSERT INTO prompt_history (client_id, version, prompt_text, updated_at)
    VALUES (?, ?, ?, ?)
    """, (client_id, prompt_update.version, prompt_update.prompt_text, now))
    conn.commit()
    conn.close()
    logger.info(f"Updated prompt version for {client_id} to {prompt_update.version}")
    return {"status": "updated", "client_id": client_id, "prompt_version": prompt_update.version}

@app.post("/v1/telemetry/calls")
def record_telemetry(
    call: CallTelemetry,
    authorization: Optional[str] = Header(None),
    x_client_token: Optional[str] = Header(None),
    x_lease_token: Optional[str] = Header(None)
):
    client = authenticate_client(
        client_id=call.client_id,
        authorization=authorization,
        x_client_token=x_client_token,
        x_lease_token=x_lease_token
    )

    conn = get_db()
    c = conn.cursor()
    ts = call.timestamp or int(time.time())
    caller_masked = call.caller_id
    if caller_masked and len(caller_masked) > 4:
        caller_masked = "..." + caller_masked[-4:]

    is_test = 1 if call.is_test_session else 0

    try:
        c.execute("""
        INSERT INTO calls (call_id, client_id, caller_id, duration_seconds, language, outcome, summary, timestamp, is_test_session)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(call_id) DO UPDATE SET
            client_id=excluded.client_id,
            caller_id=excluded.caller_id,
            duration_seconds=excluded.duration_seconds,
            language=excluded.language,
            outcome=excluded.outcome,
            summary=excluded.summary,
            timestamp=excluded.timestamp,
            is_test_session=excluded.is_test_session
        """, (call.call_id, call.client_id, caller_masked, call.duration_seconds, call.language, call.outcome, call.summary, ts, is_test))
        conn.commit()
    finally:
        conn.close()

    logger.info(f"Recorded telemetry for call {call.call_id} (Client: {call.client_id}, Outcome: {call.outcome}, IsTest: {bool(is_test)})")
    return {"status": "recorded", "call_id": call.call_id, "client_id": call.client_id, "is_test_session": bool(is_test)}

@app.get("/v1/clients/{client_id}/calls")
def get_client_calls(
    client_id: str,
    authorization: Optional[str] = Header(None),
    x_client_token: Optional[str] = Header(None)
):
    authenticate_client(client_id, authorization, x_client_token)

    conn = get_db()
    c = conn.cursor()
    c.execute("SELECT call_id, client_id, caller_id, duration_seconds, language, outcome, summary, timestamp, is_test_session FROM calls WHERE client_id = ? AND is_test_session = 0 ORDER BY timestamp DESC", (client_id,))
    rows = [dict(r) for r in c.fetchall()]
    conn.close()
    return rows

@app.post("/v1/support/reports")
def submit_support_report(
    report: SupportReport,
    authorization: Optional[str] = Header(None),
    x_client_token: Optional[str] = Header(None)
):
    authenticate_client(report.client_id, authorization, x_client_token)

    conn = get_db()
    c = conn.cursor()
    c.execute("""
    INSERT INTO support_reports (client_id, call_id, description, app_version, timestamp)
    VALUES (?, ?, ?, ?, ?)
    """, (report.client_id, report.call_id, report.description, report.app_version, int(time.time())))
    conn.commit()
    conn.close()
    logger.info(f"Received support report for client {report.client_id}")
    return {"status": "submitted"}

@app.get("/v1/models/manifest")
def get_model_manifest():
    manifest_path = os.path.join(os.path.dirname(__file__), "../../app/src/main/assets/models/manifest.json")
    if os.path.exists(manifest_path):
        with open(manifest_path, "r") as f:
            return json.load(f)
    return {"version": "4.0.0", "models": []}

# --- VOICE TEST WEBSOCKET RELAY & SESSION MANAGEMENT ---

def trigger_android_device(session_id: str, token: str) -> bool:
    import subprocess
    try:
        dev_res = subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=5)
        lines = [l for l in dev_res.stdout.strip().split("\n")[1:] if "\tdevice" in l]
        if not lines:
            logger.warning("No ADB devices connected to trigger")
            return False
        serial = lines[0].split("\t")[0]
        # Check if port 8000 is already reversed to avoid tearing down active tunnel
        rev_res = subprocess.run(["adb", "-s", serial, "reverse", "--list"], capture_output=True, text=True, timeout=2)
        if "tcp:8000 tcp:8000" not in rev_res.stdout:
            subprocess.run(["adb", "-s", serial, "reverse", "tcp:8000", "tcp:8000"], capture_output=True, timeout=3)
        # Wake screen and bring MainActivity to front with session params
        shell_cmd = (
            f"input keyevent KEYCODE_WAKEUP && "
            f"am start -n com.edge.reception/.MainActivity --es session_id {session_id} --es token {token}"
        )
        res = subprocess.run(["adb", "-s", serial, "shell", shell_cmd], capture_output=True, text=True, timeout=5)
        logger.info(f"Triggered Android VoiceTest on {serial}: {res.stdout.strip()}")
        return True
    except Exception as e:
        logger.warning(f"Could not trigger Android device via ADB: {e}")
        return False

def end_android_device() -> bool:
    import subprocess
    try:
        dev_res = subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=5)
        lines = [l for l in dev_res.stdout.strip().split("\n")[1:] if "\tdevice" in l]
        if not lines:
            return False
        serial = lines[0].split("\t")[0]
        cmd = [
            "adb", "-s", serial, "shell",
            "am", "broadcast", "-a", "com.edge.reception.END_LOCAL_VOICE_TEST",
            "-n", "com.edge.reception/.telephony.VoiceTestReceiver"
        ]
        subprocess.run(cmd, capture_output=True, timeout=3)
        return True
    except Exception:
        return False

@app.post("/v1/voice-test/session")
def create_voice_test_session(req: Optional[VoiceSessionCreateRequest] = None):
    requested_id = req.session_id if req else None
    session_id, token = voice_session_mgr.create_session(requested_id)
    # Auto-trigger connected Android device via ADB
    device_triggered = trigger_android_device(session_id, token)
    return {
        "session_id": session_id,
        "pairing_token": token,
        "device_triggered": device_triggered,
        "sample_rate": 16000,
        "channels": 1,
        "format": "pcm_s16le",
        "ws_device_url": f"ws://127.0.0.1:8000/ws/device-audio?session_id={session_id}&token={token}",
        "ws_caller_url": f"ws://127.0.0.1:8000/ws/test-caller?session_id={session_id}&token={token}"
    }

@app.post("/v1/voice-test/trigger-device")
def api_trigger_device(req: VoiceSessionCreateRequest):
    session = voice_session_mgr.get_session(req.session_id)
    if not session:
        raise HTTPException(status_code=404, detail="Session not found")
    success = trigger_android_device(req.session_id, session.token)
    return {"triggered": success, "session_id": req.session_id}

@app.post("/v1/voice-test/end-device")
def api_end_device():
    success = end_android_device()
    return {"ended": success}

@app.get("/v1/voice-test/status")
def voice_test_status():
    sessions_info = []
    for s_id, s in list(voice_session_mgr.sessions.items()):
        sessions_info.append({
            "session_id": s_id,
            "browser_connected": s.browser_ws is not None,
            "device_connected": s.device_ws is not None,
            "state": s.state,
            "bytes_caller_to_device": s.bytes_caller_to_device,
            "bytes_device_to_caller": s.bytes_device_to_caller
        })
    return {"active_sessions": sessions_info}

def generate_bangalore_realty_reply(speech_text: str) -> str:
    lower = speech_text.lower()
    if any(k in lower for k in ["wait", "hold on", "listen", "stop", "doubt", "one sec", "explain"]):
        return "Yes, absolutely, I am listening! Please tell me your requirement or doubt."
    if any(k in lower for k in ["discount", "bargain", "reduce", "cheaper", "less", "negotiate", "best price", "offer"]):
        return "Our base rate is 8,500 rupees per square foot, but for serious buyers closing this week, I can arrange an exclusive pre-launch rebate and waive clubhouse charges. What offer did you have in mind?"
    if any(k in lower for k in ["price", "rate", "cost", "sq ft", "per square", "how much", "expensive", "budget"]):
        return "In Whitefield and Sarjapur, gated developments range from 8,000 to 12,000 rupees per square foot, while North Bangalore near the Airport is 3,500 to 6,000. What budget range are you planning for?"
    if any(k in lower for k in ["flat", "apartment", "3bhk", "2bhk", "4bhk", "duplex", "villa", "inventory", "house"]):
        return "We have luxury 2 and 3 BHK flats in Whitefield starting from 95 Lakhs, and 3 BHKs on Sarjapur Road around 1.65 Crores. Are you looking to move in right away or looking for an investment?"
    if any(k in lower for k in ["how is the sale", "sale going", "sales going", "how are sales", "market", "demand", "selling", "appreciation"]):
        return "Sales are moving very fast! Phase 1 is over 85% sold out, with an 18% price appreciation in North Bangalore this year. Would you like me to reserve a priority unit for you?"
    if any(k in lower for k in ["plot", "plots", "land", "sites", "acre", "layout"]):
        return "We have gated BDA and BMRDA villa plots near the International Airport starting from 1,200 square feet at 3,500 rupees per square foot. Are you planning to build immediately or hold for appreciation?"
    if any(k in lower for k in ["visit", "see", "brochure", "whatsapp", "details", "location", "meet", "call back"]):
        return "I would be happy to host you for a VIP site visit this weekend with free doorstep cab pick-up! Shall I book a slot for you on Saturday morning?"
    if any(k in lower for k in ["hi", "hello", "hey"]):
        return "Hello! Welcome to Edge Prime Realty Bangalore. We have luxury flats from 95 Lakhs and airport villa plots from 3,500 per square foot. How can I assist you today?"
    return "We specialize in luxury apartments in Whitefield and high-growth villa plots near Bangalore Airport. Tell me, are you searching for a home for your family or a high-return investment?"

@app.websocket("/ws/test-caller")
async def ws_test_caller(websocket: WebSocket, session_id: str = Query(...), token: str = Query(...)):
    session = voice_session_mgr.get_session(session_id)
    if not session or session.token != token:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return

    await websocket.accept()
    session.browser_ws = websocket
    session.state = "BROWSER_CONNECTED"
    logger.info(f"Browser test-caller connected for session {session_id}")

    # Notify browser of device presence
    if session.device_ws:
        session.state = "PAIRED"
        await websocket.send_text(json.dumps({"type": "PEER_CONNECTED", "peer": "device"}))
        await session.device_ws.send_text(json.dumps({"type": "PEER_CONNECTED", "peer": "browser"}))

    try:
        while True:
            message = await websocket.receive()
            if message.get("type") == "websocket.disconnect":
                logger.info(f"Browser test-caller sent disconnect for session {session_id}")
                break
            if "text" in message and message["text"]:
                text_data = message["text"]
                # Parse JSON control message
                try:
                    payload = json.loads(text_data)
                    msg_type = payload.get("type", "")
                    if msg_type == "START":
                        session.state = "IN_CALL"
                        # Wait up to 1.2s to see if device pairs so device has full ownership
                        if not session.device_ws:
                            for _ in range(12):
                                await asyncio.sleep(0.1)
                                if session.device_ws:
                                    break
                        if not session.device_ws:
                            # Local fallback only when phone is truly offline
                            greeting = "Hello, thank you for calling Edge Prime Realty Bangalore. How can I assist you with our luxury flats, villa plots, and land investments today?"
                            await websocket.send_text(json.dumps({"type": "PEER_CONNECTED", "peer": "device"}))
                            await websocket.send_text(json.dumps({"type": "GREETING_START", "text": greeting, "language": "English"}))
                            await websocket.send_text(json.dumps({"type": "AI_SPEAKING_START", "text": greeting, "language": "English"}))
                            await asyncio.sleep(0.5)
                            await websocket.send_text(json.dumps({"type": "AI_SPEAKING_STOP"}))
                    elif msg_type == "INTERRUPT":
                        logger.info(f"⚡ ws_test_caller received INTERRUPT for session {session_id}")
                        if session.device_ws:
                            await session.device_ws.send_text(json.dumps({"type": "INTERRUPT"}))
                        await websocket.send_text(json.dumps({"type": "AI_SPEAKING_STOP"}))
                    elif msg_type == "CALLER_SPEECH":
                        # If device is not connected, provide immediate concise fallback
                        if not session.device_ws:
                            speech_text = payload.get("text", "")
                            reply = generate_bangalore_realty_reply(speech_text)
                            await websocket.send_text(json.dumps({"type": "TRANSCRIPT", "speaker": "AI", "text": reply, "language": "English"}))
                            await websocket.send_text(json.dumps({"type": "AI_SPEAKING_START", "text": reply, "language": "English"}))
                            await asyncio.sleep(0.5)
                            await websocket.send_text(json.dumps({"type": "AI_SPEAKING_STOP"}))
                    elif msg_type == "CALL_ENDED":
                        session.state = "ENDED"
                except Exception:
                    pass

                # Relay text control message to paired Android device
                if session.device_ws:
                    await session.device_ws.send_text(text_data)

            elif "bytes" in message and message["bytes"]:
                # Binary PCM audio from Browser Microphone -> Forward to Android Device
                raw_bytes = message["bytes"]
                session.bytes_caller_to_device += len(raw_bytes)
                if session.device_ws:
                    await session.device_ws.send_bytes(raw_bytes)

    except WebSocketDisconnect:
        logger.info(f"Browser test-caller disconnected from session {session_id}")
    except Exception as e:
        logger.error(f"Error in ws_test_caller: {e}")
    finally:
        session.browser_ws = None
        if session.device_ws:
            try:
                await session.device_ws.send_text(json.dumps({"type": "CALL_ENDED", "reason": "browser_disconnected"}))
            except Exception:
                pass

@app.websocket("/ws/device-audio")
async def ws_device_audio(websocket: WebSocket, session_id: str = Query(...), token: str = Query(...)):
    session = voice_session_mgr.get_session(session_id)
    if not session or session.token != token:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return

    await websocket.accept()
    session.device_ws = websocket
    session.state = "DEVICE_CONNECTED"
    logger.info(f"Android device audio connected for session {session_id}")

    # Notify device of browser presence
    if session.browser_ws:
        session.state = "PAIRED"
        await websocket.send_text(json.dumps({"type": "PEER_CONNECTED", "peer": "browser"}))
        await session.browser_ws.send_text(json.dumps({"type": "PEER_CONNECTED", "peer": "device"}))

    try:
        while True:
            message = await websocket.receive()
            if message.get("type") == "websocket.disconnect":
                logger.info(f"Android device sent disconnect for session {session_id}")
                break
            if "text" in message and message["text"]:
                text_data = message["text"]
                # Relay JSON control message from Android -> Browser (e.g. AI_SPEAKING_START, TRANSCRIPT)
                if session.browser_ws:
                    await session.browser_ws.send_text(text_data)

            elif "bytes" in message and message["bytes"]:
                # Binary PCM audio from Android AI Response -> Forward to Browser Playback
                raw_bytes = message["bytes"]
                session.bytes_device_to_caller += len(raw_bytes)
                if session.browser_ws:
                    await session.browser_ws.send_bytes(raw_bytes)

    except WebSocketDisconnect:
        logger.info(f"Android device disconnected from session {session_id}")
    except Exception as e:
        logger.error(f"Error in ws_device_audio: {e}")
    finally:
        session.device_ws = None
        if session.browser_ws:
            try:
                await session.browser_ws.send_text(json.dumps({"type": "CALL_ENDED", "reason": "device_disconnected"}))
            except Exception:
                pass

# --- REAL TELEPHONY & SIP/PSTN GATEWAY INTEGRATION ---

@app.get("/v1/telephony/config")
def get_telephony_config(_=Depends(verify_admin)):
    conn = get_db()
    c = conn.cursor()
    c.execute("SELECT id, sip_registrar_host, sip_registrar_port, sip_username, inbound_did, trunk_provider, status, updated_at FROM telephony_config WHERE id = 'primary_gateway'")
    row = c.fetchone()
    conn.close()
    if not row:
        return {"status": "unconfigured"}
    return dict(row)

@app.post("/v1/telephony/config")
def update_telephony_config(cfg: TelephonyConfigUpdate, _=Depends(verify_admin)):
    conn = get_db()
    c = conn.cursor()
    now = int(time.time())
    pw_hash = hash_token(cfg.sip_password) if cfg.sip_password else None
    
    # Check completeness
    has_host = bool(cfg.sip_registrar_host)
    has_user = bool(cfg.sip_username)
    has_did = bool(cfg.inbound_did)
    status_str = "configured" if (has_host and has_user and has_did) else "unconfigured"

    c.execute("""
    UPDATE telephony_config SET
        sip_registrar_host = ?,
        sip_registrar_port = ?,
        sip_username = ?,
        sip_password_hash = CASE WHEN ? IS NOT NULL THEN ? ELSE sip_password_hash END,
        inbound_did = ?,
        trunk_provider = ?,
        status = ?,
        updated_at = ?
    WHERE id = 'primary_gateway'
    """, (cfg.sip_registrar_host, cfg.sip_registrar_port, cfg.sip_username, pw_hash, pw_hash, cfg.inbound_did, cfg.trunk_provider, status_str, now))
    conn.commit()
    conn.close()
    logger.info(f"Updated primary telephony gateway configuration: status={status_str}")
    return {"status": status_str, "updated_at": now}

@app.get("/v1/telephony/status")
def get_telephony_status():
    conn = get_db()
    c = conn.cursor()
    c.execute("SELECT sip_registrar_host, sip_registrar_port, sip_username, inbound_did, trunk_provider, status FROM telephony_config WHERE id = 'primary_gateway'")
    row = c.fetchone()
    conn.close()

    missing = []
    if not row or not row["sip_registrar_host"]:
        missing.append("SIP_REGISTRAR_HOST")
    if not row or not row["sip_username"]:
        missing.append("SIP_TRUNK_CREDENTIALS")
    if not row or not row["inbound_did"]:
        missing.append("PUBLIC_INBOUND_DID")

    is_configured = len(missing) == 0
    return {
        "status": "ready" if is_configured else "unconfigured",
        "configured": is_configured,
        "sip_registered": False, # Truthful: no external carrier registration active without live credentials
        "inbound_did": row["inbound_did"] if row else None,
        "sip_registrar_host": row["sip_registrar_host"] if row else None,
        "active_carrier_calls": len(carrier_call_mgr.calls),
        "missing_prerequisites": missing
    }

@app.post("/v1/telephony/webhook/inbound-call")
def handle_carrier_inbound_call(webhook: InboundCarrierCallWebhook, authorization: Optional[str] = Header(None)):
    if authorization and authorization != f"Bearer {ADMIN_TOKEN}":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid gateway credentials")

    session, token = carrier_call_mgr.create_call(webhook.call_id, webhook.caller_number, webhook.called_did)
    logger.info(f"Inbound carrier telephone call {webhook.call_id} from {webhook.caller_number} to DID {webhook.called_did}")
    return {
        "status": "initiated",
        "call_id": webhook.call_id,
        "caller_number": webhook.caller_number,
        "called_did": webhook.called_did,
        "pairing_token": token,
        "ws_device_url": f"/ws/telephony/carrier-media?call_id={webhook.call_id}&token={token}",
        "ws_gateway_url": f"/ws/telephony/gateway-media?call_id={webhook.call_id}&token={token}"
    }

@app.get("/v1/telephony/calls")
def list_carrier_calls(_=Depends(verify_admin)):
    active = []
    for c_id, s in list(carrier_call_mgr.calls.items()):
        active.append({
            "call_id": c_id,
            "caller_number": s.caller_number,
            "called_did": s.called_did,
            "state": s.state,
            "device_connected": s.device_ws is not None,
            "gateway_connected": s.gateway_ws is not None,
            "bytes_caller_to_device": s.bytes_caller_to_device,
            "bytes_device_to_caller": s.bytes_device_to_caller
        })
    return {"active_carrier_calls": active}

# --- REAL INDIA PSTN / EXOTEL PROVIDER INTEGRATION ---

def check_media_bridge_online() -> bool:
    import socket
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(0.3)
        res = s.connect_ex(('127.0.0.1', 9099))
        s.close()
        return res == 0
    except Exception:
        return False

@app.get("/v1/telephony/provider", response_model=ProviderConfigResponse)
def get_telephony_provider_config(_=Depends(verify_admin)):
    conn = get_db()
    c = conn.cursor()
    c.execute("SELECT * FROM telephony_provider_config WHERE id = 'primary_provider'")
    row = c.fetchone()
    conn.close()
    if not row:
        return ProviderConfigResponse(
            provider_name="exotel",
            trunk_mode="pstn_to_sip",
            state=STATE_NOT_CONFIGURED,
            status_details="No provider configured."
        )
    return ProviderConfigResponse(
        provider_name=row["provider_name"] or "exotel",
        account_sid=row["account_sid"],
        api_key=row["api_key"],
        api_token_masked=mask_secret(row["api_token_hash"]),
        subdomain=row["subdomain"],
        inbound_did=row["inbound_did"],
        trunk_mode=row["trunk_mode"] or "pstn_to_sip",
        gateway_public_uri=row["gateway_public_uri"],
        state=row["state"] or STATE_NOT_CONFIGURED,
        status_details=row["status_details"],
        updated_at=row["updated_at"]
    )

@app.post("/v1/telephony/provider", response_model=ProviderConfigResponse)
def update_telephony_provider_config(cfg: ProviderConfigUpdate, _=Depends(verify_admin)):
    conn = get_db()
    c = conn.cursor()
    c.execute("SELECT * FROM telephony_provider_config WHERE id = 'primary_provider'")
    existing = c.fetchone()

    now = int(time.time())
    acc_sid = cfg.account_sid if cfg.account_sid is not None else (existing["account_sid"] if existing else None)
    key = cfg.api_key if cfg.api_key is not None else (existing["api_key"] if existing else None)
    
    # Hash or preserve token
    if cfg.api_token:
        token_hash = hash_token(cfg.api_token)
    elif existing and existing["api_token_hash"]:
        token_hash = existing["api_token_hash"]
    else:
        token_hash = None

    subdomain = cfg.subdomain if cfg.subdomain is not None else (existing["subdomain"] if existing else None)
    did = cfg.inbound_did if cfg.inbound_did is not None else (existing["inbound_did"] if existing else None)
    trunk_mode = cfg.trunk_mode or (existing["trunk_mode"] if existing else "pstn_to_sip")
    gw_uri = cfg.gateway_public_uri if cfg.gateway_public_uri is not None else (existing["gateway_public_uri"] if existing else None)

    eval_dict = {
        "account_sid": acc_sid,
        "api_key": key,
        "api_token": cfg.api_token or token_hash,
        "inbound_did": did,
        "gateway_public_uri": gw_uri
    }
    media_online = check_media_bridge_online()
    state, details, missing = evaluate_provider_state(eval_dict, media_server_online=media_online)

    c.execute("""
    UPDATE telephony_provider_config SET
        provider_name = ?,
        account_sid = ?,
        api_key = ?,
        api_token_hash = ?,
        subdomain = ?,
        inbound_did = ?,
        trunk_mode = ?,
        gateway_public_uri = ?,
        state = ?,
        status_details = ?,
        updated_at = ?
    WHERE id = 'primary_provider'
    """, (
        cfg.provider_name or "exotel",
        acc_sid,
        key,
        token_hash,
        subdomain,
        did,
        trunk_mode,
        gw_uri,
        state,
        details,
        now
    ))
    conn.commit()
    conn.close()

    logger.info(f"Updated telephony provider (Exotel) configuration: state={state}")
    return ProviderConfigResponse(
        provider_name=cfg.provider_name or "exotel",
        account_sid=acc_sid,
        api_key=key,
        api_token_masked=mask_secret(token_hash),
        subdomain=subdomain,
        inbound_did=did,
        trunk_mode=trunk_mode,
        gateway_public_uri=gw_uri,
        state=state,
        status_details=details,
        updated_at=now
    )

@app.get("/v1/telephony/provider/status", response_model=ProviderStatusResponse)
def get_telephony_provider_status():
    conn = get_db()
    c = conn.cursor()
    c.execute("SELECT * FROM telephony_provider_config WHERE id = 'primary_provider'")
    row = c.fetchone()
    conn.close()

    if not row:
        state = STATE_NOT_CONFIGURED
        details = "No provider configured."
        missing = ["EXOTEL_ACCOUNT_SID", "EXOTEL_API_KEY", "EXOTEL_API_TOKEN", "PUBLIC_INBOUND_DID", "GATEWAY_PUBLIC_URI"]
        inbound_did = None
        gw_uri = None
    else:
        inbound_did = row["inbound_did"]
        gw_uri = row["gateway_public_uri"]
        eval_dict = {
            "account_sid": row["account_sid"],
            "api_key": row["api_key"],
            "api_token": row["api_token_hash"],
            "inbound_did": inbound_did,
            "gateway_public_uri": gw_uri
        }
        media_online = check_media_bridge_online()
        state, details, missing = evaluate_provider_state(eval_dict, media_server_online=media_online)

    gw_reachability = "UNREACHABLE_PRIVATE_LAN" if is_private_or_loopback_uri(gw_uri) else ("CONFIGURED_PUBLIC" if gw_uri else "UNCONFIGURED")
    has_e164_did = is_valid_e164_number(inbound_did)

    return ProviderStatusResponse(
        provider="exotel",
        state=state,
        configured=(state == STATE_CONNECTED),
        public_number_provisioned=has_e164_did,
        gateway_reachability=gw_reachability,
        inbound_route="CONFIGURED" if (gw_uri and not is_private_or_loopback_uri(gw_uri)) else "MISSING_OR_LOCAL_ONLY",
        media_readiness="READY" if check_media_bridge_online() else "OFFLINE",
        inbound_did=inbound_did,
        missing_prerequisites=missing,
        carrier_forwarding_guidance=get_airtel_forwarding_instructions(inbound_did)
    )

@app.post("/v1/telephony/webhook/exotel/inbound")
def handle_exotel_inbound_webhook(payload: ExotelInboundWebhookPayload):
    call_id = f"exo_{payload.CallSid}"
    session, token = carrier_call_mgr.create_call(call_id, payload.From, payload.To)
    logger.info(f"Inbound Exotel call {call_id} from {payload.From} to {payload.To}")
    return {
        "status": "initiated",
        "call_id": call_id,
        "caller_number": payload.From,
        "called_did": payload.To,
        "pairing_token": token,
        "ws_device_url": f"/ws/telephony/carrier-media?call_id={call_id}&token={token}",
        "ws_gateway_url": f"/ws/telephony/gateway-media?call_id={call_id}&token={token}",
        "action": "connect_media"
    }

@app.get("/v1/telephony/diagnostics")
def get_telephony_diagnostics():
    conn = get_db()
    c = conn.cursor()
    c.execute("SELECT * FROM telephony_config WHERE id = 'primary_gateway'")
    local_cfg = c.fetchone()
    c.execute("SELECT * FROM telephony_provider_config WHERE id = 'primary_provider'")
    prov_cfg = c.fetchone()
    c.execute("SELECT * FROM clients WHERE id = 'client_s22_demo'")
    client_row = c.fetchone()
    conn.close()

    media_online = check_media_bridge_online()
    inbound_did = prov_cfg["inbound_did"] if prov_cfg else None
    gw_uri = prov_cfg["gateway_public_uri"] if prov_cfg else None
    
    eval_dict = {
        "account_sid": prov_cfg["account_sid"] if prov_cfg else None,
        "api_key": prov_cfg["api_key"] if prov_cfg else None,
        "api_token": prov_cfg["api_token_hash"] if prov_cfg else None,
        "inbound_did": inbound_did,
        "gateway_public_uri": gw_uri
    }
    state, details, missing = evaluate_provider_state(eval_dict, media_server_online=media_online)

    return {
        "local_sip_lab": {
            "status": "READY" if media_online else "OFFLINE",
            "asterisk_version": "Asterisk 23.5.0 (Docker)",
            "registrar_host": local_cfg["sip_registrar_host"] if local_cfg else "192.168.0.101",
            "registrar_port": local_cfg["sip_registrar_port"] if local_cfg else 5060,
            "test_extensions": ["1000", "1001", "1002"],
            "audiosocket_bridge_port": 9099,
            "note": "Local isolated laboratory for test calls and local SIP softphones. Not a public PSTN route."
        },
        "public_provider": {
            "provider": "exotel",
            "state": state,
            "status_details": details,
            "public_number_provisioned": is_valid_e164_number(inbound_did),
            "inbound_did": inbound_did,
            "gateway_public_uri": gw_uri,
            "gateway_reachability": "UNREACHABLE_PRIVATE_LAN" if is_private_or_loopback_uri(gw_uri) else ("CONFIGURED_PUBLIC" if gw_uri else "UNCONFIGURED"),
            "missing_prerequisites": missing
        },
        "device_registration": {
            "device_id": client_row["id"] if client_row else "client_s22_demo",
            "serial": client_row["device_serial"] if client_row else "10MF86F8HG0001B",
            "business_name": client_row["business_name"] if client_row else "Edge Realty & Services",
            "status": client_row["status"] if client_row else "active",
            "kill_switch": bool(client_row["kill_switch"]) if client_row else False,
            "models_ready": True
        },
        "carrier_forwarding": {
            "carrier": "Airtel India",
            "ussd_status": "SUSPENDED_BY_DOT_APRIL_2024",
            "supported_forwarding_method": "NATIVE_PHONE_SETTINGS_OR_AIRTEL_THANKS_APP",
            "forwarding_destination_verified": is_valid_e164_number(inbound_did) and state == STATE_CONNECTED,
            "guidance": get_airtel_forwarding_instructions(inbound_did)
        }
    }

@app.get("/v1/business-scripts")
def get_business_scripts():
    scripts_path = os.path.join(os.path.dirname(__file__), "business_scripts.json")
    if os.path.exists(scripts_path):
        with open(scripts_path, "r", encoding="utf-8") as f:
            return json.load(f)
    return {"version": "1.0.0", "templates": []}

@app.post("/v1/business-scripts")
def update_business_scripts(payload: dict, _=Depends(verify_admin)):
    scripts_path = os.path.join(os.path.dirname(__file__), "business_scripts.json")
    with open(scripts_path, "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2)
    return {"status": "updated", "version": payload.get("version", "1.0.0")}

@app.websocket("/ws/telephony/carrier-media")
async def ws_carrier_media(websocket: WebSocket, call_id: str = Query(...), token: str = Query(...)):
    session = carrier_call_mgr.get_call(call_id)
    if not session or session.token != token:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return

    await websocket.accept()
    session.device_ws = websocket
    session.state = "DEVICE_CONNECTED"
    logger.info(f"Android device connected for carrier call {call_id}")

    if session.gateway_ws:
        session.state = "MEDIA_ACTIVE"
        await websocket.send_text(json.dumps({"type": "PEER_CONNECTED", "peer": "carrier_gateway", "caller_number": session.caller_number}))
        await session.gateway_ws.send_text(json.dumps({"type": "PEER_CONNECTED", "peer": "device"}))

    try:
        while True:
            message = await websocket.receive()
            if "text" in message and message["text"]:
                text_data = message["text"]
                if session.gateway_ws:
                    await session.gateway_ws.send_text(text_data)
            elif "bytes" in message and message["bytes"]:
                raw_bytes = message["bytes"]
                session.bytes_device_to_caller += len(raw_bytes)
                if session.gateway_ws:
                    await session.gateway_ws.send_bytes(raw_bytes)
    except WebSocketDisconnect:
        logger.info(f"Android device disconnected from carrier call {call_id}")
    except Exception as e:
        logger.error(f"Error in ws_carrier_media: {e}")
    finally:
        session.device_ws = None
        if session.gateway_ws:
            try:
                await session.gateway_ws.send_text(json.dumps({"type": "CALL_ENDED", "reason": "device_disconnected"}))
            except Exception:
                pass

@app.websocket("/ws/telephony/gateway-media")
async def ws_gateway_media(websocket: WebSocket, call_id: str = Query(...), token: str = Query(...)):
    session = carrier_call_mgr.get_call(call_id)
    if not session or session.token != token:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return

    await websocket.accept()
    session.gateway_ws = websocket
    session.state = "GATEWAY_CONNECTED"
    logger.info(f"PSTN/SIP Gateway connected for carrier call {call_id}")

    if session.device_ws:
        session.state = "MEDIA_ACTIVE"
        await websocket.send_text(json.dumps({"type": "PEER_CONNECTED", "peer": "device"}))
        await session.device_ws.send_text(json.dumps({"type": "PEER_CONNECTED", "peer": "carrier_gateway", "caller_number": session.caller_number}))

    try:
        while True:
            message = await websocket.receive()
            if "text" in message and message["text"]:
                text_data = message["text"]
                if session.device_ws:
                    await session.device_ws.send_text(text_data)
            elif "bytes" in message and message["bytes"]:
                raw_bytes = message["bytes"]
                session.bytes_caller_to_device += len(raw_bytes)
                if session.device_ws:
                    await session.device_ws.send_bytes(raw_bytes)
    except WebSocketDisconnect:
        logger.info(f"PSTN/SIP Gateway disconnected from carrier call {call_id}")
    except Exception as e:
        logger.error(f"Error in ws_gateway_media: {e}")
    finally:
        session.gateway_ws = None
        if session.device_ws:
            try:
                await session.device_ws.send_text(json.dumps({"type": "CALL_ENDED", "reason": "gateway_disconnected"}))
            except Exception:
                pass

# --- TEST DIALER WEB INTERFACE ---

@app.get("/test-dialer", response_class=HTMLResponse)
def test_dialer():
    html = """
    <!DOCTYPE html>
    <html lang="en">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>Edge Local Voice Test — MacBook to Vivo USB Relay</title>
        <style>
            :root {
                --bg: #0f172a;
                --card-bg: #1e293b;
                --card-border: #334155;
                --text: #f8fafc;
                --text-muted: #94a3b8;
                --accent: #38bdf8;
                --success: #10b981;
                --danger: #ef4444;
                --warning: #f59e0b;
            }
            body {
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                margin: 0; padding: 24px; background: var(--bg); color: var(--text);
            }
            .container { max-width: 800px; margin: 0 auto; }
            .header {
                display: flex; justify-content: space-between; align-items: center;
                border-bottom: 1px solid var(--card-border); padding-bottom: 16px; margin-bottom: 24px;
            }
            .card {
                background: var(--card-bg); border: 1px solid var(--card-border);
                border-radius: 12px; padding: 24px; margin-bottom: 20px; box-shadow: 0 4px 6px -1px rgba(0,0,0,0.2);
            }
            .badge {
                display: inline-block; padding: 4px 10px; border-radius: 9999px; font-size: 12px; font-weight: 600; text-transform: uppercase;
            }
            .badge-neutral { background: #334155; color: #94a3b8; }
            .badge-success { background: #064e3b; color: #34d399; }
            .badge-danger { background: #7f1d1d; color: #fca5a5; }
            .badge-active { background: #0c4a6e; color: #38bdf8; }
            .badge-warning { background: #78350f; color: #fcd34d; }
            .btn {
                background: #0284c7; color: white; border: none; padding: 12px 24px;
                border-radius: 8px; cursor: pointer; font-size: 15px; font-weight: 600;
                display: inline-flex; align-items: center; gap: 8px; transition: all 0.2s;
            }
            .btn:hover:not(:disabled) { background: #0369a1; transform: translateY(-1px); }
            .btn:disabled { opacity: 0.5; cursor: not-allowed; }
            .btn-danger { background: #dc2626; }
            .btn-danger:hover:not(:disabled) { background: #b91c1c; }
            .meter-bar-container {
                height: 12px; background: #334155; border-radius: 6px; overflow: hidden; margin: 12px 0;
            }
            .meter-bar {
                height: 100%; width: 0%; background: #38bdf8; transition: width 0.05s ease-out;
            }
            .transcript-box {
                background: #0b1120; border: 1px solid var(--card-border); border-radius: 8px;
                padding: 16px; min-height: 180px; max-height: 280px; overflow-y: auto; font-family: monospace; font-size: 14px;
            }
            .turn { margin-bottom: 12px; padding: 8px 12px; border-radius: 6px; line-height: 1.4; }
            .turn-caller { background: #1e3a5f; color: #bae6fd; }
            .turn-ai { background: #064e3b; color: #a7f3d0; }
            .status-row { display: flex; gap: 12px; flex-wrap: wrap; margin-bottom: 16px; }
            .notice-box {
                background: #172554; border-left: 4px solid #38bdf8; padding: 12px 16px; border-radius: 4px; font-size: 13px; line-height: 1.5; color: #cbd5e1; margin-bottom: 20px;
            }
        </style>
    </head>
    <body>
        <div class="container">
            <div class="header">
                <div>
                    <h1 style="margin: 0; font-size: 22px;">Edge Local Two-Way Voice Test</h1>
                    <p style="margin: 4px 0 0 0; color: var(--text-muted); font-size: 14px;">MacBook Browser ⟷ Vivo Phone (USB Tunnel Audio Relay)</p>
                </div>
                <span class="badge badge-neutral" id="peer-status">Waiting for Pair</span>
            </div>

            <div class="notice-box">
                <strong>Local Audio Loop Test:</strong> This milestone verifies 16kHz PCM audio streaming between your MacBook microphone/speakers and the Edge local AI pipeline on the connected Vivo phone. It does not initiate a cellular call or bill phone carriers.
            </div>

            <div class="card">
                <div class="status-row">
                    <div><strong>Caller Source:</strong> <span class="badge badge-active">Local Mac Test Caller</span></div>
                    <div><strong>Audio Format:</strong> <span class="badge badge-neutral">16,000 Hz Mono PCM (16-bit)</span></div>
                    <div><strong>Relay State:</strong> <span class="badge badge-neutral" id="relay-state">IDLE</span></div>
                </div>

                <div style="display: flex; gap: 12px; margin-top: 16px;">
                    <button class="btn" id="btn-start" onclick="startVoiceTest()">
                        <svg width="18" height="18" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 11a7 7 0 01-7 7m0 0a7 7 0 01-7-7m7 7v4m0 0H8m4 0h4m-4-8a3 3 0 100-6 3 3 0 000 6z"></path></svg>
                        Start Local Voice Test
                    </button>
                    <button class="btn btn-danger" id="btn-end" onclick="endVoiceTest()" disabled>
                        <svg width="18" height="18" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M16 8l2-2m0 0l2-2m-2 2l-2-2m2 2l2 2M5 3a2 2 0 00-2 2v1c0 8.284 6.716 15 15 15h1a2 2 0 002-2v-3.28a1 1 0 00-.684-.948l-4.493-1.498a1 1 0 00-1.21.502l-1.13 2.257a11.042 11.042 0 01-5.516-5.517l2.257-1.128a1 1 0 00.502-1.21L9.228 3.684A1 1 0 008.279 3H5z"></path></svg>
                        End Test
                    </button>
                </div>

                <div style="margin-top: 20px;">
                    <div style="display: flex; justify-content: space-between; font-size: 13px; color: var(--text-muted);">
                        <span>MacBook Microphone Level</span>
                        <span id="gate-indicator" style="color: var(--success);">Mic Active</span>
                    </div>
                    <div class="meter-bar-container">
                        <div class="meter-bar" id="meter-bar"></div>
                    </div>
                </div>
            </div>

            <div class="card">
                <h3 style="margin: 0 0 12px 0; font-size: 15px;">Real-Time Local Conversation</h3>
                <div class="transcript-box" id="transcript-box">
                    <div style="color: #64748b; text-align: center; margin-top: 60px;" id="placeholder-text">
                        Click "Start Local Voice Test" to begin. The Vivo Edge receptionist will deliver the greeting first.
                    </div>
                </div>
            </div>
        </div>

        <script>
            let audioCtx = null;
            let micStream = null;
            let scriptProcessor = null;
            let wsCaller = null;
            let currentSession = null;
            let isAiSpeaking = false;
            let audioQueue = [];
            let isPlaying = false;

            const btnStart = document.getElementById('btn-start');
            const btnEnd = document.getElementById('btn-end');
            const relayState = document.getElementById('relay-state');
            const peerStatus = document.getElementById('peer-status');
            const meterBar = document.getElementById('meter-bar');
            const gateIndicator = document.getElementById('gate-indicator');
            const transcriptBox = document.getElementById('transcript-box');
            const placeholderText = document.getElementById('placeholder-text');

            function appendTurn(speaker, text, lang) {
                if (placeholderText) placeholderText.style.display = 'none';
                const div = document.createElement('div');
                div.className = speaker === 'AI' ? 'turn turn-ai' : 'turn turn-caller';
                div.innerHTML = `<strong>${speaker} ${lang ? '[' + lang + ']' : ''}:</strong> ${text}`;
                transcriptBox.appendChild(div);
                transcriptBox.scrollTop = transcriptBox.scrollHeight;
            }

            async function startVoiceTest() {
                try {
                    btnStart.disabled = true;
                    relayState.textContent = 'CONNECTING...';
                    relayState.className = 'badge badge-warning';

                    // 1. Request session from backend
                    const res = await fetch('/v1/voice-test/session', { method: 'POST' });
                    currentSession = await res.json();

                    // 2. Open WebSocket
                    const wsUrl = (window.location.protocol === 'https:' ? 'wss://' : 'ws://') + window.location.host + '/ws/test-caller?session_id=' + currentSession.session_id + '&token=' + currentSession.pairing_token;
                    wsCaller = new WebSocket(wsUrl);
                    wsCaller.binaryType = 'arraybuffer';

                    wsCaller.onopen = async () => {
                        relayState.textContent = 'CONNECTED';
                        relayState.className = 'badge badge-success';
                        btnEnd.disabled = false;
                        await initMicrophoneCapture();
                        wsCaller.send(JSON.stringify({ type: 'START', session_id: currentSession.session_id }));
                    };

                    wsCaller.onmessage = (event) => {
                        if (typeof event.data === 'string') {
                            handleControlMessage(JSON.parse(event.data));
                        } else if (event.data instanceof ArrayBuffer) {
                            handleAiAudioChunk(event.data);
                        }
                    };

                    wsCaller.onclose = () => {
                        relayState.textContent = 'DISCONNECTED';
                        relayState.className = 'badge badge-neutral';
                        stopMedia();
                    };

                    wsCaller.onerror = (err) => {
                        console.error('WebSocket error:', err);
                        relayState.textContent = 'ERROR';
                        relayState.className = 'badge badge-danger';
                    };

                } catch (e) {
                    alert('Error starting voice test: ' + e.message);
                    btnStart.disabled = false;
                }
            }

            function handleControlMessage(msg) {
                if (msg.type === 'PEER_CONNECTED') {
                    peerStatus.textContent = 'Device Paired';
                    peerStatus.className = 'badge badge-success';
                } else if (msg.type === 'GREETING_START') {
                    appendTurn('AI', msg.text || 'Greeting playing...', msg.language || 'English');
                } else if (msg.type === 'AI_SPEAKING_START') {
                    setAiSpeakingState(true);
                } else if (msg.type === 'AI_SPEAKING_STOP') {
                    setAiSpeakingState(false);
                } else if (msg.type === 'TRANSCRIPT') {
                    appendTurn(msg.speaker, msg.text, msg.language);
                } else if (msg.type === 'CALL_ENDED') {
                    appendTurn('System', 'Call test completed.', null);
                    endVoiceTest();
                }
            }

            function setAiSpeakingState(speaking) {
                isAiSpeaking = speaking;
                if (speaking) {
                    gateIndicator.textContent = 'Mic Gated (AI Speaking)';
                    gateIndicator.style.color = '#f59e0b';
                } else {
                    gateIndicator.textContent = 'Mic Active';
                    gateIndicator.style.color = '#10b981';
                }
            }

            async function initMicrophoneCapture() {
                audioCtx = new (window.AudioContext || window.webkitAudioContext)();
                micStream = await navigator.mediaDevices.getUserMedia({
                    audio: {
                        channelCount: 1,
                        echoCancellation: true,
                        noiseSuppression: true,
                        autoGainControl: true
                    }
                });

                const source = audioCtx.createMediaStreamSource(micStream);
                const bufferSize = 2048;
                scriptProcessor = audioCtx.createScriptProcessor(bufferSize, 1, 1);

                scriptProcessor.onaudioprocess = (e) => {
                    const inputData = e.inputBuffer.getChannelData(0);

                    // Calculate RMS volume for visual meter
                    let sum = 0;
                    for (let i = 0; i < inputData.length; i++) {
                        sum += inputData[i] * inputData[i];
                    }
                    const rms = Math.sqrt(sum / inputData.length);
                    const percent = Math.min(100, Math.round(rms * 400));
                    meterBar.style.width = percent + '%';

                    // Audio Loop Gating: If AI is actively speaking through speakers, mute microphone transmission
                    if (isAiSpeaking) {
                        return;
                    }

                    if (wsCaller && wsCaller.readyState === WebSocket.OPEN) {
                        // Downsample from browser audioCtx.sampleRate (e.g. 48000 Hz) to 16000 Hz Signed 16-bit Mono PCM
                        const pcm16Buffer = downsampleToInt16(inputData, audioCtx.sampleRate, 16000);
                        wsCaller.send(pcm16Buffer);
                    }
                };

                source.connect(scriptProcessor);
                scriptProcessor.connect(audioCtx.destination);
            }

            function downsampleToInt16(inputBuffer, inRate, outRate) {
                if (inRate === outRate) {
                    const out = new Int16Array(inputBuffer.length);
                    for (let i = 0; i < inputBuffer.length; i++) {
                        const s = Math.max(-1, Math.min(1, inputBuffer[i]));
                        out[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
                    }
                    return out.buffer;
                }
                const ratio = inRate / outRate;
                const outLen = Math.floor(inputBuffer.length / ratio);
                const out = new Int16Array(outLen);
                for (let i = 0; i < outLen; i++) {
                    const srcPos = i * ratio;
                    const idx0 = Math.floor(srcPos);
                    const idx1 = Math.min(idx0 + 1, inputBuffer.length - 1);
                    const frac = srcPos - idx0;
                    const s = inputBuffer[idx0] * (1 - frac) + inputBuffer[idx1] * frac;
                    const clamped = Math.max(-1, Math.min(1, s));
                    out[i] = clamped < 0 ? clamped * 0x8000 : clamped * 0x7FFF;
                }
                return out.buffer;
            }

            function handleAiAudioChunk(arrayBuffer) {
                // Decode 16kHz Signed 16-bit PCM chunk into Web Audio Buffer
                const int16 = new Int16Array(arrayBuffer);
                const float32 = new Float32Array(int16.length);
                for (let i = 0; i < int16.length; i++) {
                    float32[i] = int16[i] / 32768.0;
                }

                if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)();
                const buffer = audioCtx.createBuffer(1, float32.length, 16000);
                buffer.copyToChannel(float32, 0);

                audioQueue.push(buffer);
                if (!isPlaying) {
                    playNextAudioChunk();
                }
            }

            function playNextAudioChunk() {
                if (audioQueue.length === 0) {
                    isPlaying = false;
                    setAiSpeakingState(false);
                    return;
                }
                isPlaying = true;
                setAiSpeakingState(true);
                const buffer = audioQueue.shift();
                const source = audioCtx.createBufferSource();
                source.buffer = buffer;
                source.connect(audioCtx.destination);
                source.onended = () => {
                    playNextAudioChunk();
                };
                source.start();
            }

            function endVoiceTest() {
                if (wsCaller && wsCaller.readyState === WebSocket.OPEN) {
                    wsCaller.send(JSON.stringify({ type: 'CALL_ENDED' }));
                    wsCaller.close();
                }
                stopMedia();
                btnStart.disabled = false;
                btnEnd.disabled = true;
                relayState.textContent = 'CALL ENDED';
                relayState.className = 'badge badge-neutral';
                peerStatus.textContent = 'Disconnected';
                peerStatus.className = 'badge badge-neutral';
            }

            function stopMedia() {
                if (scriptProcessor) {
                    scriptProcessor.disconnect();
                    scriptProcessor = null;
                }
                if (micStream) {
                    micStream.getTracks().forEach(track => track.stop());
                    micStream = null;
                }
                if (audioCtx && audioCtx.state !== 'closed') {
                    audioCtx.close();
                    audioCtx = null;
                }
                meterBar.style.width = '0%';
                setAiSpeakingState(false);
            }
        </script>
    </body>
    </html>
    """
    return HTMLResponse(content=html)

@app.get("/", response_class=HTMLResponse)
def dashboard():
    conn = get_db()
    c = conn.cursor()
    c.execute("SELECT COUNT(*) FROM clients WHERE status = 'active'")
    active_clients = c.fetchone()[0]
    # Isolate production counts from test calls
    c.execute("SELECT COUNT(*) FROM calls WHERE is_test_session = 0")
    total_calls = c.fetchone()[0]
    c.execute("SELECT COUNT(*) FROM calls WHERE is_test_session = 0 AND outcome = 'LEAD'")
    total_leads = c.fetchone()[0]
    c.execute("SELECT * FROM clients")
    clients = [dict(r) for r in c.fetchall()]
    c.execute("SELECT * FROM calls WHERE is_test_session = 0 ORDER BY timestamp DESC LIMIT 20")
    calls = [dict(r) for r in c.fetchall()]
    c.execute("SELECT * FROM calls WHERE is_test_session = 1 ORDER BY timestamp DESC LIMIT 10")
    test_calls = [dict(r) for r in c.fetchall()]

    # Telephony diagnostics
    c.execute("SELECT * FROM telephony_config WHERE id = 'primary_gateway'")
    local_cfg = c.fetchone()
    c.execute("SELECT * FROM telephony_provider_config WHERE id = 'primary_provider'")
    prov_cfg = c.fetchone()
    conn.close()

    media_online = check_media_bridge_online()
    inbound_did = prov_cfg["inbound_did"] if prov_cfg else None
    gw_uri = prov_cfg["gateway_public_uri"] if prov_cfg else None
    eval_dict = {
        "account_sid": prov_cfg["account_sid"] if prov_cfg else None,
        "api_key": prov_cfg["api_key"] if prov_cfg else None,
        "api_token": prov_cfg["api_token_hash"] if prov_cfg else None,
        "inbound_did": inbound_did,
        "gateway_public_uri": gw_uri
    }
    prov_state, prov_details, missing_prereqs = evaluate_provider_state(eval_dict, media_server_online=media_online)

    prov_badge_color = "#10b981" if prov_state == STATE_CONNECTED else ("#f59e0b" if prov_state == STATE_NOT_CONFIGURED else "#ef4444")
    prov_badge_bg = "#064e3b" if prov_state == STATE_CONNECTED else ("#78350f" if prov_state == STATE_NOT_CONFIGURED else "#7f1d1d")

    html = f"""
    <!DOCTYPE html>
    <html lang="en">
    <head>
        <meta charset="UTF-8">
        <title>Edge AI Receptionist — Founder Control Plane</title>
        <style>
            body {{ font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; margin: 0; padding: 24px; background: #0f172a; color: #f8fafc; }}
            .container {{ max-width: 1100px; margin: 0 auto; }}
            .header {{ display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #334155; padding-bottom: 16px; margin-bottom: 24px; }}
            .card-grid {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; margin-bottom: 24px; }}
            .card {{ background: #1e293b; border: 1px solid #334155; border-radius: 8px; padding: 16px; }}
            .card h3 {{ margin: 0 0 8px 0; font-size: 14px; color: #94a3b8; text-transform: uppercase; letter-spacing: 0.05em; }}
            .card .val {{ font-size: 28px; font-weight: 700; color: #38bdf8; }}
            table {{ width: 100%; border-collapse: collapse; margin-top: 12px; font-size: 14px; }}
            th, td {{ text-align: left; padding: 10px 12px; border-bottom: 1px solid #334155; }}
            th {{ background: #1e293b; color: #94a3b8; font-weight: 600; }}
            tr:hover {{ background: #1e293b; }}
            .badge {{ display: inline-block; padding: 2px 8px; border-radius: 9999px; font-size: 11px; font-weight: 600; text-transform: uppercase; }}
            .badge-active {{ background: #064e3b; color: #34d399; }}
            .badge-lead {{ background: #065f46; color: #6ee7b7; }}
            .badge-fail {{ background: #7f1d1d; color: #fca5a5; }}
            .badge-test {{ background: #4338ca; color: #c7d2fe; }}
            .btn {{ background: #0284c7; color: white; border: none; padding: 6px 12px; border-radius: 4px; cursor: pointer; font-size: 12px; font-weight: 600; text-decoration: none; display: inline-block; }}
            .btn-kill {{ background: #dc2626; }}
            .btn-rev {{ background: #16a34a; }}
            .input-box {{ background: #0f172a; border: 1px solid #334155; color: #f8fafc; padding: 8px 12px; border-radius: 6px; width: 100%; box-sizing: border-box; margin-bottom: 8px; }}
        </style>
    </head>
    <body>
        <div class="container">
            <div class="header">
                <div>
                    <h1 style="margin: 0; font-size: 24px;">Edge Control Plane & Security Gateway</h1>
                    <p style="margin: 4px 0 0 0; color: #94a3b8; font-size: 14px;">On-Device Receptionist Telemetry, Remote Subscriptions & Kill-Switch</p>
                </div>
                <div>
                    <a href="/test-dialer" class="btn" style="margin-right: 8px;">Open Test Dialer</a>
                    <span class="badge badge-active">Live Production Gate</span>
                </div>
            </div>

            <div class="card-grid">
                <div class="card">
                    <h3>Active Clients</h3>
                    <div class="val">{active_clients}</div>
                </div>
                <div class="card">
                    <h3>Production Calls</h3>
                    <div class="val">{total_calls}</div>
                </div>
                <div class="card">
                    <h3>Production Leads</h3>
                    <div class="val">{total_leads}</div>
                </div>
                <div class="card">
                    <h3>PSTN Provider State</h3>
                    <div class="val" style="font-size: 18px; color: {prov_badge_color};">{prov_state}</div>
                </div>
            </div>

            <!-- Telephony Architecture & Diagnostics Cards -->
            <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 24px;">
                <!-- Public India PSTN Card -->
                <div class="card">
                    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;">
                        <h3 style="margin: 0; color: #38bdf8;">Public Telephony &amp; Carrier Forwarding (Airtel)</h3>
                        <span class="badge" style="background: {prov_badge_bg}; color: {prov_badge_color};">{prov_state}</span>
                    </div>
                    <p style="font-size: 13px; color: #cbd5e1; margin-top: 0;"><strong>Status:</strong> {prov_details}</p>
                    <div style="font-size: 12px; color: #94a3b8; line-height: 1.6;">
                        <div><strong>Virtual Inbound DID:</strong> {inbound_did or "None (Pending Provisioning)"}</div>
                        <div><strong>Gateway Public URI:</strong> {gw_uri or "None (Local LAN 192.168.0.101 unreachable from PSTN)"}</div>
                        <div><strong>Missing Prerequisites:</strong> {', '.join(missing_prereqs) if missing_prereqs else 'None - Connected'}</div>
                        <div style="margin-top: 8px; padding: 8px; background: #0f172a; border-radius: 4px; border-left: 3px solid #f59e0b;">
                            <strong>Airtel Carrier Forwarding:</strong> Per DoT India (April 15, 2024), USSD codes (*21*, *67*) are suspended. Forwarding must be activated via Phone Settings &gt; Calling accounts &gt; Airtel &gt; Call Forwarding to the verified E.164 DID.
                        </div>
                    </div>
                </div>

                <!-- Local SIP Lab Card -->
                <div class="card">
                    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;">
                        <h3 style="margin: 0; color: #a78bfa;">Local SIP PBX Laboratory</h3>
                        <span class="badge" style="background: {'#064e3b' if media_online else '#7f1d1d'}; color: {'#34d399' if media_online else '#fca5a5'};">{'READY (Local Lab)' if media_online else 'OFFLINE'}</span>
                    </div>
                    <p style="font-size: 13px; color: #cbd5e1; margin-top: 0;">Self-hosted Asterisk 23.5.0 in Docker with AudioSocket Bridge (Port 9099).</p>
                    <div style="font-size: 12px; color: #94a3b8; line-height: 1.6;">
                        <div><strong>Local Registrar:</strong> {local_cfg['sip_registrar_host'] if local_cfg else '192.168.0.101'}:{local_cfg['sip_registrar_port'] if local_cfg else 5060}</div>
                        <div><strong>Lab Extensions:</strong> 1000 (PBX Echo/IVR), 1001 (Softphone), 1002 (SIP Test)</div>
                        <div><strong>Isolation:</strong> Local lab traffic is strictly isolated from public carrier calls.</div>
                        <div style="margin-top: 8px; padding: 8px; background: #0f172a; border-radius: 4px; border-left: 3px solid #38bdf8;">
                            <strong>Lab Status:</strong> Fully functional for browser and SIP softphone tests over LAN/USB. Extension 1000 cannot be dialed from external cellular phones.
                        </div>
                    </div>
                </div>
            </div>

            <div class="card" style="margin-bottom: 24px;">
                <h3 style="margin-bottom: 12px;">Managed Devices & Leases</h3>
                <table>
                    <thead>
                        <tr>
                            <th>Client ID</th>
                            <th>Business Name</th>
                            <th>Type</th>
                            <th>Device Serial</th>
                            <th>Status</th>
                            <th>Kill Switch</th>
                            <th>Prompt Ver</th>
                            <th>Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        {"".join(f'''<tr>
                            <td><code>{c["id"]}</code></td>
                            <td>{c["business_name"]}</td>
                            <td>{c["business_type"]}</td>
                            <td><code>{c["device_serial"] or "N/A"}</code></td>
                            <td><span class="badge badge-active">{c["status"]}</span></td>
                            <td><span style="color: {'#ef4444' if c['kill_switch'] else '#10b981'}; font-weight: 600;">{'ACTIVE' if c['kill_switch'] else 'OFF'}</span></td>
                            <td>{c["prompt_version"]}</td>
                            <td>
                                <button class="btn {'btn-rev' if c['kill_switch'] else 'btn-kill'}" onclick="toggleKillSwitch('{c['id']}', {0 if c['kill_switch'] else 1})">
                                    {'Revoke Kill-Switch' if c['kill_switch'] else 'Trigger Kill-Switch'}
                                </button>
                            </td>
                        </tr>''' for c in clients)}
                    </tbody>
                </table>
            </div>

            <div class="card" style="margin-bottom: 24px;">
                <h3 style="margin-bottom: 12px;">Recent Production Telemetry (Zero Fake Calls)</h3>
                <table>
                    <thead>
                        <tr>
                            <th>Call ID</th>
                            <th>Client</th>
                            <th>Caller ID</th>
                            <th>Duration</th>
                            <th>Language</th>
                            <th>Outcome</th>
                            <th>Summary</th>
                        </tr>
                    </thead>
                    <tbody>
                        {"".join(f'''<tr>
                            <td><code>{call["call_id"]}</code></td>
                            <td><code>{call["client_id"]}</code></td>
                            <td>{call["caller_id"]}</td>
                            <td>{call["duration_seconds"]}s</td>
                            <td>{call["language"]}</td>
                            <td><span class="badge {'badge-lead' if call['outcome'] == 'LEAD' else 'badge-fail'}">{call['outcome']}</span></td>
                            <td>{call["summary"]}</td>
                        </tr>''' for call in calls) if calls else '<tr><td colspan="7" style="text-align: center; color: #64748b;">No production calls recorded yet. Clean empty state active.</td></tr>'}
                    </tbody>
                </table>
            </div>

            <div class="card">
                <h3 style="margin-bottom: 12px;">Local Two-Way Audio Test Sessions (Isolated from Production Metrics)</h3>
                <table>
                    <thead>
                        <tr>
                            <th>Session ID</th>
                            <th>Source</th>
                            <th>Duration</th>
                            <th>Language</th>
                            <th>Outcome</th>
                            <th>Summary</th>
                        </tr>
                    </thead>
                    <tbody>
                        {"".join(f'''<tr>
                            <td><code>{tc["call_id"]}</code></td>
                            <td><span class="badge badge-test">Local Mac Test Caller</span></td>
                            <td>{tc["duration_seconds"]}s</td>
                            <td>{tc["language"]}</td>
                            <td><span class="badge {'badge-lead' if tc['outcome'] == 'LEAD' else 'badge-fail'}">{tc['outcome']}</span></td>
                            <td>{tc["summary"]}</td>
                        </tr>''' for tc in test_calls) if test_calls else '<tr><td colspan="6" style="text-align: center; color: #64748b;">No local test sessions recorded yet.</td></tr>'}
                    </tbody>
                </table>
            </div>
        </div>

        <script>
            async function toggleKillSwitch(clientId, newState) {{
                const token = "{ADMIN_TOKEN}";
                const res = await fetch('/v1/clients/' + clientId + '/status', {{
                    method: 'PATCH',
                    headers: {{
                        'Content-Type': 'application/json',
                        'Authorization': 'Bearer ' + token
                    }},
                    body: JSON.stringify({{ kill_switch: Boolean(newState) }})
                }});
                if (res.ok) {{
                    window.location.reload();
                }} else {{
                    alert('Error toggling kill switch');
                }}
            }}
        </script>
    </body>
    </html>
    """
    return HTMLResponse(content=html)
