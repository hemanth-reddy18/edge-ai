import os
import sqlite3
import hmac
import hashlib
import time
import json
from typing import Optional, List
from fastapi import FastAPI, HTTPException, Header, Depends, Query
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

DB_PATH = os.path.join(os.path.dirname(__file__), "edge_control.db")
SECRET_KEY = os.environ.get("EDGE_SECRET_KEY", "edge_founder_secret_key_demo_2026")
ADMIN_TOKEN = os.environ.get("EDGE_ADMIN_TOKEN", "admin_secret_token_2026")

app = FastAPI(title="Edge AI Receptionist — Founder Control Plane", version="3.0.0")

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
        prompt_version TEXT DEFAULT 'v1.0',
        created_at INTEGER
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
        FOREIGN KEY(client_id) REFERENCES clients(id)
    )
    """)
    c.execute("""
    CREATE TABLE IF NOT EXISTS support_reports (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        client_id TEXT,
        call_id TEXT,
        description TEXT,
        app_version TEXT,
        timestamp INTEGER
    )
    """)
    # Seed default client if empty (without fake calls)
    c.execute("SELECT COUNT(*) FROM clients")
    if c.fetchone()[0] == 0:
        c.execute("""
        INSERT INTO clients (id, business_name, business_type, device_serial, status, kill_switch, lease_duration_sec, prompt_version, created_at)
        VALUES ('client_s22_demo', 'Edge Realty & Services', 'Real Estate', 'ce09171998abec250b7e', 'active', 0, 86400, 'v1.0', ?)
        """, (int(time.time()),))
    conn.commit()
    conn.close()

init_db()

# Models
class ClientCreate(BaseModel):
    id: str
    business_name: str
    business_type: str
    device_serial: Optional[str] = None

class StatusUpdate(BaseModel):
    status: Optional[str] = None # active, suspended
    kill_switch: Optional[bool] = None

class CallTelemetry(BaseModel):
    call_id: str
    client_id: str
    caller_id: Optional[str] = "Anonymous"
    duration_seconds: int
    language: str
    outcome: str
    summary: str
    timestamp: Optional[int] = None

class SupportReport(BaseModel):
    client_id: str
    call_id: Optional[str] = None
    description: str
    app_version: Optional[str] = "1.0.0"

def verify_admin(authorization: Optional[str] = Header(None)):
    if not authorization or authorization != f"Bearer {ADMIN_TOKEN}":
        raise HTTPException(status_code=401, detail="Unauthorized admin token")
    return True

# Cryptographic Lease Generator
def sign_lease(client_id: str, expiry_timestamp: int, kill_switch: bool) -> str:
    payload = f"{client_id}:{expiry_timestamp}:{int(kill_switch)}"
    signature = hmac.new(SECRET_KEY.encode(), payload.encode(), hashlib.sha256).hexdigest()
    return f"{payload}:{signature}"

@app.get("/health")
def health():
    return {
        "status": "healthy",
        "service": "Edge AI Receptionist Founder Control Plane",
        "timestamp": int(time.time())
    }

@app.post("/v1/auth/login")
def login(creds: dict):
    if creds.get("token") == ADMIN_TOKEN:
        return {"access_token": ADMIN_TOKEN, "token_type": "bearer"}
    raise HTTPException(status_code=401, detail="Invalid credentials")

@app.get("/v1/clients")
def list_clients():
    conn = get_db()
    c = conn.cursor()
    c.execute("SELECT * FROM clients")
    rows = [dict(r) for r in c.fetchall()]
    conn.close()
    return rows

@app.post("/v1/clients")
def create_client(client: ClientCreate, _=Depends(verify_admin)):
    conn = get_db()
    c = conn.cursor()
    try:
        c.execute("""
        INSERT INTO clients (id, business_name, business_type, device_serial, status, kill_switch, lease_duration_sec, prompt_version, created_at)
        VALUES (?, ?, ?, ?, 'active', 0, 86400, 'v1.0', ?)
        """, (client.id, client.business_name, client.business_type, client.device_serial, int(time.time())))
        conn.commit()
    except sqlite3.IntegrityError:
        conn.close()
        raise HTTPException(status_code=400, detail="Client ID already exists")
    conn.close()
    return {"status": "created", "client_id": client.id}

@app.get("/v1/clients/{client_id}/lease")
def get_client_lease(client_id: str):
    conn = get_db()
    c = conn.cursor()
    c.execute("SELECT * FROM clients WHERE id = ?", (client_id,))
    client = c.fetchone()
    conn.close()
    if not client:
        raise HTTPException(status_code=404, detail="Client not found")

    client = dict(client)
    now = int(time.time())
    expiry = now + client["lease_duration_sec"]
    kill_switch = bool(client["kill_switch"])
    is_active = (client["status"] == "active") and not kill_switch

    signed_token = sign_lease(client_id, expiry, kill_switch)

    return {
        "client_id": client_id,
        "is_active": is_active,
        "kill_switch_active": kill_switch,
        "lease_expiry_timestamp": expiry,
        "signed_token": signed_token,
        "prompt_version": client["prompt_version"]
    }

@app.patch("/v1/clients/{client_id}/status")
def update_client_status(client_id: str, update: StatusUpdate, _=Depends(verify_admin)):
    conn = get_db()
    c = conn.cursor()
    if update.status is not None:
        c.execute("UPDATE clients SET status = ? WHERE id = ?", (update.status, client_id))
    if update.kill_switch is not None:
        c.execute("UPDATE clients SET kill_switch = ? WHERE id = ?", (int(update.kill_switch), client_id))
    conn.commit()
    conn.close()
    return {"status": "updated", "client_id": client_id}

@app.post("/v1/telemetry/calls")
def record_telemetry(call: CallTelemetry):
    conn = get_db()
    c = conn.cursor()
    ts = call.timestamp or int(time.time())
    try:
        c.execute("""
        INSERT OR REPLACE INTO calls (call_id, client_id, caller_id, duration_seconds, language, outcome, summary, timestamp)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, (call.call_id, call.client_id, call.caller_id, call.duration_seconds, call.language, call.outcome, call.summary, ts))
        conn.commit()
    finally:
        conn.close()
    return {"status": "recorded", "call_id": call.call_id}

@app.get("/v1/clients/{client_id}/calls")
def get_client_calls(client_id: str):
    conn = get_db()
    c = conn.cursor()
    c.execute("SELECT * FROM calls WHERE client_id = ? ORDER BY timestamp DESC", (client_id,))
    rows = [dict(r) for r in c.fetchall()]
    conn.close()
    return rows

@app.post("/v1/support/reports")
def submit_support_report(report: SupportReport):
    conn = get_db()
    c = conn.cursor()
    c.execute("""
    INSERT INTO support_reports (client_id, call_id, description, app_version, timestamp)
    VALUES (?, ?, ?, ?, ?)
    """, (report.client_id, report.call_id, report.description, report.app_version, int(time.time())))
    conn.commit()
    conn.close()
    return {"status": "submitted"}

@app.get("/v1/models/manifest")
def get_model_manifest():
    manifest_path = os.path.join(os.path.dirname(__file__), "../../app/src/main/assets/models/manifest.json")
    if os.path.exists(manifest_path):
        with open(manifest_path, "r") as f:
            return json.load(f)
    return {"version": "1.0.0", "models": []}

@app.get("/", response_class=HTMLResponse)
def dashboard():
    conn = get_db()
    c = conn.cursor()
    c.execute("SELECT COUNT(*) FROM clients WHERE status = 'active'")
    active_clients = c.fetchone()[0]
    c.execute("SELECT COUNT(*) FROM calls")
    total_calls = c.fetchone()[0]
    c.execute("SELECT COUNT(*) FROM calls WHERE outcome = 'LEAD'")
    leads = c.fetchone()[0]
    c.execute("SELECT COUNT(*) FROM calls WHERE outcome = 'FAIL'")
    fails = c.fetchone()[0]
    c.execute("SELECT * FROM calls ORDER BY timestamp DESC LIMIT 10")
    calls = [dict(r) for r in c.fetchall()]
    c.execute("SELECT * FROM clients")
    clients = [dict(r) for r in c.fetchall()]
    conn.close()

    lead_rate = f"{(leads / total_calls * 100):.1f}%" if total_calls > 0 else "0.0%"

    calls_html = ""
    for call in calls:
        badge_color = "bg-emerald-900 text-emerald-300" if call["outcome"] == "LEAD" else "bg-rose-900 text-rose-300"
        calls_html += f"""
        <tr class="border-b border-slate-800 text-sm hover:bg-slate-800/40">
            <td class="py-3 px-4 font-mono text-slate-300">{call['call_id']}</td>
            <td class="py-3 px-4">{call['caller_id']}</td>
            <td class="py-3 px-4"><span class="px-2 py-1 rounded text-xs {badge_color}">{call['outcome']}</span></td>
            <td class="py-3 px-4 text-sky-400">{call['language']}</td>
            <td class="py-3 px-4 text-slate-400 max-w-md truncate">{call['summary']}</td>
        </tr>
        """

    clients_html = ""
    for client in clients:
        status_color = "text-emerald-400" if client["status"] == "active" and not client["kill_switch"] else "text-rose-400"
        kill_label = "KILL SWITCH: ACTIVE" if client["kill_switch"] else "NORMAL"
        clients_html += f"""
        <div class="bg-slate-800/70 border border-slate-700 p-4 rounded-xl flex items-center justify-between">
            <div>
                <h4 class="font-bold text-white text-base">{client['business_name']} <span class="text-xs text-sky-400 font-mono">({client['id']})</span></h4>
                <p class="text-xs text-slate-400 mt-1">Type: {client['business_type']} | Serial: {client['device_serial'] or 'Unbound'}</p>
                <p class="text-xs {status_color} mt-1 font-semibold">Status: {client['status'].upper()} • {kill_label}</p>
            </div>
            <div class="flex space-x-2">
                <form action="/v1/clients/{client['id']}/status" method="POST" onsubmit="event.preventDefault(); toggleKill('{client['id']}', {0 if client['kill_switch'] else 1});">
                    <button class="px-3 py-1.5 bg-rose-600 hover:bg-rose-700 text-white text-xs font-bold rounded-lg transition">
                        {'Deactivate Kill Switch' if client['kill_switch'] else 'Trigger Kill Switch'}
                    </button>
                </form>
            </div>
        </div>
        """

    html = f"""<!DOCTYPE html>
<html lang="en" class="dark">
<head>
    <meta charset="UTF-8">
    <title>Edge AI Receptionist — Founder Control Plane</title>
    <script src="https://cdn.tailwindcss.com"></script>
</head>
<body class="bg-slate-950 text-slate-100 min-h-screen p-8 font-sans">
    <div class="max-w-6xl mx-auto space-y-8">
        <!-- Header -->
        <header class="flex items-center justify-between border-b border-slate-800 pb-6">
            <div>
                <h1 class="text-2xl font-black tracking-tight text-white flex items-center gap-2">
                    <span class="text-sky-400">EDGE</span> AI Receptionist Control Plane
                </h1>
                <p class="text-xs text-slate-400 mt-1">Founder Management Plane • Connected to Galaxy S22 via ADB Reverse</p>
            </div>
            <div class="flex items-center gap-3">
                <span class="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-semibold bg-emerald-950 border border-emerald-800 text-emerald-400">
                    <span class="w-2 h-2 rounded-full bg-emerald-400 animate-pulse"></span>
                    Backend Active
                </span>
            </div>
        </header>

        <!-- Metrics Overview -->
        <section class="grid grid-cols-1 md:grid-cols-4 gap-4">
            <div class="bg-slate-900 border border-slate-800 p-5 rounded-2xl">
                <p class="text-xs uppercase tracking-wider text-slate-500 font-bold">Active Clients</p>
                <p class="text-3xl font-black text-white mt-2">{active_clients}</p>
            </div>
            <div class="bg-slate-900 border border-slate-800 p-5 rounded-2xl">
                <p class="text-xs uppercase tracking-wider text-slate-500 font-bold">Total Calls Processed</p>
                <p class="text-3xl font-black text-sky-400 mt-2">{total_calls}</p>
            </div>
            <div class="bg-slate-900 border border-slate-800 p-5 rounded-2xl">
                <p class="text-xs uppercase tracking-wider text-slate-500 font-bold">Qualified Leads</p>
                <p class="text-3xl font-black text-emerald-400 mt-2">{leads}</p>
            </div>
            <div class="bg-slate-900 border border-slate-800 p-5 rounded-2xl">
                <p class="text-xs uppercase tracking-wider text-slate-500 font-bold">Lead Conversion Rate</p>
                <p class="text-3xl font-black text-amber-400 mt-2">{lead_rate}</p>
            </div>
        </section>

        <!-- Client Management & Kill Switch -->
        <section class="space-y-4">
            <h3 class="text-lg font-bold text-white">Clients &amp; Remote Kill-Switch</h3>
            <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
                {clients_html}
            </div>
        </section>

        <!-- Real-time Call Activity -->
        <section class="bg-slate-900 border border-slate-800 rounded-2xl p-6">
            <h3 class="text-lg font-bold text-white mb-4">Consented Call Activity Feed</h3>
            <div class="overflow-x-auto">
                <table class="w-full text-left">
                    <thead>
                        <tr class="border-b border-slate-800 text-xs font-bold text-slate-400 uppercase tracking-wider">
                            <th class="py-3 px-4">Call ID</th>
                            <th class="py-3 px-4">Caller ID</th>
                            <th class="py-3 px-4">Outcome</th>
                            <th class="py-3 px-4">Language</th>
                            <th class="py-3 px-4">Summary</th>
                        </tr>
                    </thead>
                    <tbody>
                        {calls_html}
                    </tbody>
                </table>
            </div>
        </section>
    </div>

    <script>
        async function toggleKill(clientId, newState) {{
            try {{
                const res = await fetch('/v1/clients/' + clientId + '/status', {{
                    method: 'PATCH',
                    headers: {{
                        'Content-Type': 'application/json',
                        'Authorization': 'Bearer admin_secret_token_2026'
                    }},
                    body: JSON.stringify({{ kill_switch: Boolean(newState) }})
                }});
                if (res.ok) {{
                    window.location.reload();
                }} else {{
                    alert('Failed to update status');
                }}
            }} catch(e) {{
                alert('Error: ' + e);
            }}
        }}
    </script>
</body>
</html>"""
    return html
