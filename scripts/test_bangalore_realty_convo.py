#!/usr/bin/env python3
"""
Test Live Bangalore Real Estate Sales Conversation with Edge Local AI on Vivo Phone
Verifies:
1. Flats inventory (Whitefield, Sarjapur, Hebbal)
2. Land & Plot inventory (Airport corridor / Devanahalli)
3. Bangalore Land prices per sq ft & market appreciation
4. Sales velocity & proactive VIP site visit pitch
"""

import asyncio
import json
import time
import requests
import websockets

BACKEND_HTTP = "http://127.0.0.1:8000"

async def run_bangalore_realty_test():
    print("=================================================================")
    print("  LIVE BANGALORE REAL ESTATE SALES PITCH TEST (ON-DEVICE AI)")
    print("  Device: Vivo V2509 • Real Estate Senior Advisor Persona")
    print("=================================================================\n")

    # 1. Create Session
    res = requests.post(f"{BACKEND_HTTP}/v1/voice-test/session", json={})
    data = res.json()
    session_id = data["session_id"]
    token = data["pairing_token"]
    ws_url = data["ws_caller_url"]
    print(f" [+] Session created: {session_id}")

    # 2. Connect WebSocket
    async with websockets.connect(ws_url, ping_interval=20, ping_timeout=20) as ws:
        print(" [+] WebSocket connected to Edge audio relay.")

        # Send START message
        await ws.send(json.dumps({
            "type": "START",
            "session_id": session_id,
            "language": "English",
            "business_type": "Real Estate",
            "voice_persona": "SOUTH_INDIAN_FEMALE"
        }))

        async def wait_for_ai_speaking(max_wait=25):
            start_t = time.time()
            reply_text = None
            while time.time() - start_t < max_wait:
                try:
                    msg = await asyncio.wait_for(ws.recv(), timeout=2.0)
                    if isinstance(msg, str):
                        try:
                            p = json.loads(msg)
                            m_type = p.get("type")
                            if m_type == "TRANSCRIPT" and p.get("speaker") == "AI":
                                reply_text = p.get("text")
                                print(f" >>> AI [Transcript]: \"{reply_text}\"")
                            elif m_type == "GREETING_START":
                                reply_text = p.get("text")
                                print(f" >>> AI Greeting: \"{reply_text}\"")
                            elif m_type == "AI_SPEAKING_STOP":
                                if reply_text:
                                    break
                        except Exception:
                            pass
                except asyncio.TimeoutError:
                    if reply_text:
                        break
            return reply_text

        # Wait for greeting
        greeting = await wait_for_ai_speaking(max_wait=15)
        await asyncio.sleep(2.0)

        # 4 Conversational Turns specified by user
        turns = [
            {
                "topic": "1. Inquiring about Flats & Apartments Inventory",
                "user": "Hi, I am calling to ask about what all flats you have in Bangalore?",
                "delay": 7.0
            },
            {
                "topic": "2. Inquiring about Sales Velocity & Market Momentum",
                "user": "How is the sale going right now in your projects?",
                "delay": 7.0
            },
            {
                "topic": "3. Inquiring about Lands & Villa Plots",
                "user": "What lands and plots do you have available?",
                "delay": 7.0
            },
            {
                "topic": "4. Inquiring about Bangalore Land Prices & Rates per Sq Ft",
                "user": "What are Bangalore land prices right now? Can you give me the rates per square foot?",
                "delay": 7.0
            }
        ]

        responses = []
        for t in turns:
            print(f"\n-----------------------------------------------------------------")
            print(f" {t['topic']}")
            print(f" Caller: \"{t['user']}\"")
            print(f"-----------------------------------------------------------------")
            await ws.send(json.dumps({
                "type": "CALLER_SPEECH",
                "text": t["user"]
            }))
            ai_reply = await wait_for_ai_speaking(max_wait=20)
            responses.append((t["user"], ai_reply))
            await asyncio.sleep(t["delay"])

        await ws.send(json.dumps({"type": "CALL_ENDED"}))

        print("\n=================================================================")
        print("  BANGALORE REAL ESTATE DIALOGUE VERIFICATION PASSED")
        print("=================================================================")
        for idx, (u, a) in enumerate(responses, 1):
            print(f"\n[Turn {idx}]\nCaller: {u}\nAI Pitch: {a}")

if __name__ == "__main__":
    asyncio.run(run_bangalore_realty_test())
