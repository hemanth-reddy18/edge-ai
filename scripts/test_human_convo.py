import asyncio
import json
import websockets
import urllib.request

SESSION_URL = "http://127.0.0.1:8000/v1/voice-test/session"

async def run_dialogue():
    req = urllib.request.Request(SESSION_URL, data=b"{}", headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req) as resp:
        session_data = json.loads(resp.read().decode())
    
    session_id = session_data["session_id"]
    token = session_data["pairing_token"]
    ws_url = f"ws://127.0.0.1:8000/ws/test-caller?session_id={session_id}&token={token}"

    print(f"\n[+] Connected to test session: {session_id}")

    async with websockets.connect(ws_url) as ws:
        await ws.send(json.dumps({
            "type": "START",
            "session_id": session_id,
            "language": "English",
            "voice_persona": "SOUTH_INDIAN_FEMALE",
            "business_type": "Real Estate"
        }))

        # Wait for greeting
        while True:
            raw = await asyncio.wait_for(ws.recv(), timeout=8.0)
            if isinstance(raw, str):
                msg = json.loads(raw)
                if msg.get("type") == "GREETING_START":
                    print(f"\n🤖 AI Greeting: \"{msg.get('text')}\"")
                elif msg.get("type") == "AI_SPEAKING_STOP":
                    break

        turns = [
            "Can you tell me the land prices in Bangalore right now per sq ft?",
            "Can you reduce the price a bit or give any discount?",
            "What gated plots do you have near the airport?",
            "Can you schedule a site visit for this weekend?"
        ]

        for turn in turns:
            print(f"\n🗣️ User: \"{turn}\"")
            await ws.send(json.dumps({
                "type": "CALLER_SPEECH",
                "text": turn
            }))

            while True:
                raw = await asyncio.wait_for(ws.recv(), timeout=8.0)
                if isinstance(raw, str):
                    msg = json.loads(raw)
                    if msg.get("type") == "TRANSCRIPT" and msg.get("speaker") == "AI":
                        print(f"🤖 AI: \"{msg.get('text')}\"")
                    elif msg.get("type") == "AI_SPEAKING_STOP":
                        break
            await asyncio.sleep(0.5)

        await ws.send(json.dumps({"type": "CALL_ENDED"}))

    print("\n✅ NATURAL CONVERSATION DIALOGUE TEST COMPLETED SUCCESSFULLY\n")

if __name__ == "__main__":
    asyncio.run(run_dialogue())
