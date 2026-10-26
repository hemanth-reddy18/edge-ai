import asyncio
import websockets
import json
import requests
import time
import subprocess

async def test_live_voice_loop():
    print("=== Edge Local Two-Way Voice Test over USB Relay ===")
    
    # 1. Create pairing session on FastAPI backend
    res = requests.post("http://127.0.0.1:8000/v1/voice-test/session")
    session = res.json()
    s_id = session["session_id"]
    token = session["pairing_token"]
    print(f"[1/5] Session created: {s_id}")

    # 2. Wake phone, ensure MainActivity is foreground to prevent Vivo PACKAGE_FREEZE, then trigger via ADB broadcast
    subprocess.run("adb -s 10MF86F8HG0001B shell 'input keyevent KEYCODE_WAKEUP && am start -n com.edge.reception/.MainActivity'", shell=True, check=True)
    await asyncio.sleep(0.5)
    cmd = f"adb -s 10MF86F8HG0001B shell 'am broadcast -a com.edge.reception.START_LOCAL_VOICE_TEST -n com.edge.reception/.telephony.VoiceTestReceiver -f 0x10000000 --es session_id {s_id} --es token {token}'"
    subprocess.run(cmd, shell=True, check=True)
    print("[2/5] Broadcast sent to Vivo phone (10MF86F8HG0001B)")

    await asyncio.sleep(1.0)

    # 3. Connect test caller WebSocket from Mac
    uri = f"ws://127.0.0.1:8000/ws/test-caller?session_id={s_id}&token={token}"
    async with websockets.connect(uri) as ws:
        print("[3/5] Test Caller WebSocket connected")

        # Send START
        await ws.send(json.dumps({"type": "START", "session_id": s_id}))

        greeting_received = False
        greeting_pcm_bytes = 0

        # Wait for dynamic greeting to complete
        start_time = time.time()
        while time.time() - start_time < 35:
            try:
                remaining = max(1.0, 35 - (time.time() - start_time))
                msg = await asyncio.wait_for(ws.recv(), timeout=min(remaining, 8.0))
                if isinstance(msg, str):
                    data = json.loads(msg)
                    print(f" -> Control received: {data.get('type')} - {data.get('text', '')}")
                    if data.get("type") == "GREETING_COMPLETED":
                        greeting_received = True
                        break
                elif isinstance(msg, bytes):
                    greeting_pcm_bytes += len(msg)
            except asyncio.TimeoutError:
                if greeting_received:
                    break
                continue

        print(f"[4/5] Greeting completed: {greeting_received}, PCM audio bytes: {greeting_pcm_bytes}")
        assert greeting_pcm_bytes > 0, "No greeting PCM audio received from phone"

        # Give 500ms pause after greeting
        await asyncio.sleep(0.5)

        # 4. Stream 1.5 seconds of speech PCM audio to phone
        print("[5/5] Streaming caller speech PCM frames to phone...")
        # 1.5 seconds of 16kHz audio = 24000 samples * 2 bytes = 48000 bytes
        chunk_size = 640
        caller_pcm = bytes([0x40, 0x10] * 24000)
        for offset in range(0, len(caller_pcm), chunk_size):
            await ws.send(caller_pcm[offset:offset+chunk_size])
            await asyncio.sleep(0.02) # 20ms cadence

        print(" -> Finished sending caller speech. Signaling CALLER_SPEECH_DONE...")
        await ws.send(json.dumps({"type": "CALLER_SPEECH_DONE"}))
        print(" -> Waiting for on-device ASR + LLM + TTS response...")

        ai_reply_received = False
        ai_reply_pcm_bytes = 0
        ai_reply_text = ""

        # Wait for AI response
        start_time = time.time()
        while time.time() - start_time < 35:
            try:
                remaining = max(1.0, 35 - (time.time() - start_time))
                msg = await asyncio.wait_for(ws.recv(), timeout=min(remaining, 8.0))
                if isinstance(msg, str):
                    data = json.loads(msg)
                    print(f" -> AI Control: {data.get('type')} - {data.get('text', '')}")
                    if data.get("type") == "TRANSCRIPT" and data.get("speaker") == "AI":
                        ai_reply_text = data.get("text")
                    if data.get("type") == "AI_SPEAKING_STOP":
                        ai_reply_received = True
                        break
                elif isinstance(msg, bytes):
                    ai_reply_pcm_bytes += len(msg)
            except asyncio.TimeoutError:
                if ai_reply_received:
                    break
                continue

        print(f"AI response received: {ai_reply_received}, Text: '{ai_reply_text}', PCM bytes: {ai_reply_pcm_bytes}")
        assert ai_reply_pcm_bytes > 0, "No AI response PCM audio received from phone"

        # End test session
        await ws.send(json.dumps({"type": "CALL_ENDED"}))
        await asyncio.sleep(1.0)

    print("=== Voice Test Passed Successfully ===")

if __name__ == "__main__":
    asyncio.run(test_live_voice_loop())
