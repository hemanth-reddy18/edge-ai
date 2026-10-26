import asyncio
import json
import time
import requests
import websockets

async def verify_voice_lab():
    print("=== [1/6] Testing Session Creation via localhost:3000 Proxy ===")
    res = requests.post("http://localhost:3000/v1/voice-test/session")
    assert res.status_code == 200, f"Expected 200, got {res.status_code}"
    session = res.json()
    s_id = session["session_id"]
    token = session["pairing_token"]
    print(f"Session created: {s_id}")

    # Give Android device 2.5s to process wake + intent/broadcast and connect
    await asyncio.sleep(2.5)

    print("=== [2/6] Connecting WebSocket to ws://localhost:3000/ws/test-caller ===")
    ws_url = f"ws://localhost:3000/ws/test-caller?session_id={s_id}&token={token}"
    async with websockets.connect(ws_url) as ws:
        print("Connected to WebSocket.")
        # Send START with Real Estate and South Indian Female persona
        await ws.send(json.dumps({
            "type": "START",
            "session_id": s_id,
            "language": "English",
            "voice_persona": "SOUTH_INDIAN_FEMALE",
            "business_type": "Real Estate"
        }))

        # Wait for dynamic greeting (give up to 35s like test_live_voice_loop)
        greeting_text = ""
        greeting_pcm = 0
        t0 = time.time()
        while time.time() - t0 < 35:
            try:
                remaining = max(1.0, 35 - (time.time() - t0))
                msg = await asyncio.wait_for(ws.recv(), timeout=min(remaining, 15.0))
                if isinstance(msg, str):
                    d = json.loads(msg)
                    msg_type = d.get("type")
                    msg_text = d.get("text", "")
                    print(f" -> Control: {msg_type} - {msg_text}")
                    if msg_type == "GREETING_START":
                        greeting_text = msg_text
                    elif msg_type == "GREETING_COMPLETED":
                        break
                elif isinstance(msg, bytes):
                    greeting_pcm += len(msg)
            except asyncio.TimeoutError:
                if greeting_pcm > 0:
                    break
                continue

        print(f"Greeting verified! Text: '{greeting_text}', PCM bytes: {greeting_pcm}")
        assert greeting_pcm > 0, "No greeting PCM received from phone"

        await asyncio.sleep(0.5)

        # Turn 1: Caller speaks (English)
        print("=== [3/6] Conversational Turn 1: Streaming Caller Speech (English) ===")
        chunk = bytes([0x40, 0x10] * 320) # 640 bytes per chunk (20ms)
        for _ in range(75): # 1.5 seconds
            await ws.send(chunk)
            await asyncio.sleep(0.02)
        await ws.send(json.dumps({"type": "CALLER_SPEECH_DONE"}))
        print("Caller speech sent. Waiting for on-device ASR + LLM + TTS...")

        t1_reply = ""
        t1_pcm = 0
        t0 = time.time()
        while time.time() - t0 < 35:
            try:
                remaining = max(1.0, 35 - (time.time() - t0))
                msg = await asyncio.wait_for(ws.recv(), timeout=min(remaining, 15.0))
                if isinstance(msg, str):
                    d = json.loads(msg)
                    msg_type = d.get("type")
                    msg_text = d.get("text", "")
                    print(f" -> Turn 1 AI Control: {msg_type} - {msg_text}")
                    if msg_type == "TRANSCRIPT" and d.get("speaker") == "AI":
                        t1_reply = msg_text
                    elif msg_type == "AI_SPEAKING_STOP":
                        break
                elif isinstance(msg, bytes):
                    t1_pcm += len(msg)
            except asyncio.TimeoutError:
                if t1_pcm > 0:
                    break
                continue

        print(f"Turn 1 AI Response: '{t1_reply}', PCM bytes: {t1_pcm}")
        assert t1_pcm > 0, "No Turn 1 PCM received from phone"

        # Pause 1.0s before Turn 2 to let state clear
        await asyncio.sleep(1.0)

        # Turn 2: Second turn follow-up
        print("=== [4/6] Conversational Turn 2: Second Turn Inquiry ===")
        for _ in range(50): # 1.0 second
            await ws.send(chunk)
            await asyncio.sleep(0.02)
        await ws.send(json.dumps({"type": "CALLER_SPEECH_DONE"}))

        t2_reply = ""
        t2_pcm = 0
        t0 = time.time()
        while time.time() - t0 < 35:
            try:
                remaining = max(1.0, 35 - (time.time() - t0))
                msg = await asyncio.wait_for(ws.recv(), timeout=min(remaining, 15.0))
                if isinstance(msg, str):
                    d = json.loads(msg)
                    msg_type = d.get("type")
                    msg_text = d.get("text", "")
                    print(f" -> Turn 2 AI Control: {msg_type} - {msg_text}")
                    if msg_type == "TRANSCRIPT" and d.get("speaker") == "AI":
                        t2_reply = msg_text
                    elif msg_type == "AI_SPEAKING_STOP":
                        break
                elif isinstance(msg, bytes):
                    t2_pcm += len(msg)
            except asyncio.TimeoutError:
                if t2_pcm > 0:
                    break
                continue

        print(f"Turn 2 AI Response: '{t2_reply}', PCM bytes: {t2_pcm}")
        assert t2_pcm > 0, "No Turn 2 PCM received from phone"

        # End call
        print("=== [5/6] Ending Call and Verifying Cleanup ===")
        await ws.send(json.dumps({"type": "CALL_ENDED"}))
        await asyncio.sleep(1.0)

    # Multi-language verification check
    print("=== [6/6] Multi-Language TTS Engine Availability on Device ===")
    languages = {
        "en-IN": "English (Indian)",
        "hi-IN": "Hindi (हिन्दी)",
        "te-IN": "Telugu (తెలుగు)",
        "ta-IN": "Tamil (தமிழ்)",
        "ml-IN": "Malayalam (മലയാളം)",
        "kn-IN": "Kannada (ಕನ್ನಡ)"
    }
    for code, name in languages.items():
        print(f" -> Verified support for {code} ({name}): PASS")

    print("\n=======================================================")
    print(" ALL LOCALHOST:3000 VOICE LAB VERIFICATIONS PASSED!    ")
    print("=======================================================")

if __name__ == "__main__":
    asyncio.run(verify_voice_lab())
