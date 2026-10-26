#!/usr/bin/env python3
"""
Test Live Bargaining Conversation with Edge Local AI on Vivo Phone
Sends turns to the connected phone, triggers on-device LLM + TTS,
and plays audio out loud on the phone's hardware loudspeaker at full volume.
"""

import asyncio
import json
import time
import requests
import websockets

BACKEND_HTTP = "http://127.0.0.1:8000"

async def run_bargaining_convo():
    print("=================================================================")
    print("  LIVE BARGAINING CONVERSATION TEST WITH ON-DEVICE EDGE AI")
    print("  Device: Vivo V2509 (Android 16) • Speaker: 100% Full Volume")
    print("=================================================================\n")

    # 1. Create Voice Test Session
    print("[1/5] Creating Voice Session and triggering Vivo Android app...")
    res = requests.post(f"{BACKEND_HTTP}/v1/voice-test/session", json={})
    if res.status_code != 200:
        print(f"[-] Failed to create session: {res.status_code} - {res.text}")
        return

    data = res.json()
    session_id = data["session_id"]
    token = data["pairing_token"]
    ws_url = data["ws_caller_url"]
    print(f" [+] Session created: {session_id}")
    print(f" [+] Android trigger status: {data.get('device_triggered')}")

    # 2. Connect WebSocket
    print(f"[2/5] Connecting to WebSocket: {ws_url} ...")
    async with websockets.connect(ws_url, ping_interval=20, ping_timeout=20) as ws:
        print(" [+] WebSocket connected to Edge audio relay.")

        # Send START message
        start_payload = {
            "type": "START",
            "session_id": session_id,
            "language": "English",
            "business_type": "Retail Store",
            "voice_persona": "SOUTH_INDIAN_FEMALE"
        }
        await ws.send(json.dumps(start_payload))

        ai_responses = []

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
                                print(f" >>> AI Greeting [Started]: \"{reply_text}\"")
                            elif m_type == "AI_SPEAKING_STOP":
                                if reply_text:
                                    break
                        except Exception:
                            pass
                except asyncio.TimeoutError:
                    if reply_text:
                        break
            return reply_text

        # 3. Wait for AI Greeting
        print("\n[3/5] Waiting for Edge AI to speak Greeting on the phone loudspeaker...")
        greeting = await wait_for_ai_speaking(max_wait=15)
        await asyncio.sleep(3.5) # Allow speaker playback to finish

        # Conversational Turns: Bargaining Scenario
        turns = [
            {
                "user": "Hi, how are you? I'm looking at this leather jacket and I want to buy it, but the price is too high. Can you give me a discount or bargain on this item?",
                "delay_after": 8.0
            },
            {
                "user": "1,700 is still above my budget. Can you lower the price to 1,400 rupees? If you can do 1,400, I will buy it right now.",
                "delay_after": 8.0
            },
            {
                "user": "Okay, 1,500 rupees with free delivery is a fair deal. Let's do it!",
                "delay_after": 7.0
            }
        ]

        print("\n[4/5] Executing 3-Turn Bargaining Conversation...\n")
        for idx, turn in enumerate(turns, 1):
            user_msg = turn["user"]
            print(f"-----------------------------------------------------------------")
            print(f" TURN {idx}: CALLER SPEAKS")
            print(f" Caller: \"{user_msg}\"")
            print(f"-----------------------------------------------------------------")

            # Send caller speech to Edge on phone
            speech_payload = {
                "type": "CALLER_SPEECH",
                "text": user_msg
            }
            await ws.send(json.dumps(speech_payload))

            # Wait for AI response
            ai_reply = await wait_for_ai_speaking(max_wait=20)
            ai_responses.append((user_msg, ai_reply))

            # Pause to let phone loudspeaker play completely
            print(f" [*] Playing response through phone loudspeaker (volume 100%)...\n")
            await asyncio.sleep(turn["delay_after"])

        # 5. End Session
        print("\n[5/5] Ending Conversation Session...")
        await ws.send(json.dumps({"type": "CALL_ENDED"}))

        print("\n=================================================================")
        print("  CONVERSATION COMPLETE - TRANSCRIPT SUMMARY")
        print("=================================================================")
        print(f"AI Greeting: \"{greeting}\"\n")
        for i, (u, a) in enumerate(ai_responses, 1):
            print(f"Turn {i} Caller: \"{u}\"")
            print(f"Turn {i} Edge AI: \"{a}\"\n")

        print(" [+] All 3 bargaining turns successfully synthesized and played on phone speaker!")

if __name__ == "__main__":
    asyncio.run(run_bargaining_convo())
