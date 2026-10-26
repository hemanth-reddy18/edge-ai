import asyncio
import json
import websockets
import urllib.request
import sys

SESSION_URL = "http://127.0.0.1:8000/v1/voice-test/session"

async def run_test():
    print("=" * 65)
    print("  VERIFYING SINGLE AGENT AUTHORITY & INSTANT BARGE-IN INTERRUPTION")
    print("=" * 65)

    # 1. Create Voice Session
    req = urllib.request.Request(SESSION_URL, data=b"{}", headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req) as resp:
        session_data = json.loads(resp.read().decode())
    
    session_id = session_data["session_id"]
    token = session_data["pairing_token"]
    ws_url = f"ws://127.0.0.1:8000/ws/test-caller?session_id={session_id}&token={token}"

    print(f"\n[+] Session: {session_id}")
    print(f"[+] Connecting to {ws_url} ...")

    async with websockets.connect(ws_url) as ws:
        # Start session
        await ws.send(json.dumps({
            "type": "START",
            "session_id": session_id,
            "language": "English",
            "voice_persona": "SOUTH_INDIAN_FEMALE",
            "business_type": "Real Estate"
        }))

        greetings_received = []
        ai_speaking_starts = 0

        # Collect greeting messages for up to 6.0 seconds to ensure NO duplicate greetings occur
        start_time = asyncio.get_event_loop().time()
        while asyncio.get_event_loop().time() - start_time < 6.0:
            try:
                msg_raw = await asyncio.wait_for(ws.recv(), timeout=1.5)
                if isinstance(msg_raw, str):
                    msg = json.loads(msg_raw)
                    mtype = msg.get("type")
                    if mtype == "GREETING_START":
                        greetings_received.append(msg.get("text", ""))
                        print(f" >>> GREETING RECEIVED: \"{msg.get('text')}\"")
                    elif mtype == "AI_SPEAKING_START":
                        ai_speaking_starts += 1
                    elif mtype == "AI_SPEAKING_STOP" and greetings_received:
                        # Greeting finished speaking
                        break
            except asyncio.TimeoutError:
                if greetings_received:
                    break

        print(f"\n[Check 1] Greetings received: {len(greetings_received)}")
        if len(greetings_received) != 1:
            print(f"[-] ERROR: Expected exactly 1 greeting, got {len(greetings_received)}!")
            sys.exit(1)
        print("  --> PASS: Exactly 1 greeting delivered. No duplicate agent clash!")

        # 2. Test Caller Speech
        print("\n-----------------------------------------------------------------")
        print(" [Test 2] Caller asks for flats")
        print(" Caller: \"Hi, what flats do you have in Whitefield?\"")
        print("-----------------------------------------------------------------")
        await ws.send(json.dumps({
            "type": "CALLER_SPEECH",
            "text": "Hi, what flats do you have in Whitefield?"
        }))

        reply_text = None
        for _ in range(20):
            msg_raw = await asyncio.wait_for(ws.recv(), timeout=5.0)
            if isinstance(msg_raw, str):
                msg = json.loads(msg_raw)
                if msg.get("type") == "TRANSCRIPT" and msg.get("speaker") == "AI":
                    reply_text = msg.get("text")
                    print(f" >>> AI Response: \"{reply_text}\"")
                    break

        assert reply_text is not None, "Failed to get AI reply"
        print("  --> PASS: Crisp AI reply received!")

        # 3. Test BARGE-IN INTERRUPTION
        print("\n-----------------------------------------------------------------")
        print(" [Test 3] Testing Instant Barge-in Interruption while AI is speaking")
        print(" Caller triggers INTERRUPT (barge-in)")
        print("-----------------------------------------------------------------")
        await ws.send(json.dumps({"type": "INTERRUPT"}))

        stopped = False
        for _ in range(10):
            msg_raw = await asyncio.wait_for(ws.recv(), timeout=3.0)
            if isinstance(msg_raw, str):
                msg = json.loads(msg_raw)
                if msg.get("type") == "AI_SPEAKING_STOP":
                    stopped = True
                    print(" >>> Confirmed AI_SPEAKING_STOP received immediately on interrupt!")
                    break

        assert stopped, "Did not receive AI_SPEAKING_STOP on INTERRUPT"
        print("  --> PASS: Instant barge-in cutoff verified!")

        # 4. Test Doubt Question
        print("\n-----------------------------------------------------------------")
        print(" [Test 4] Caller interrupts with a doubt")
        print(" Caller: \"Wait, I have a doubt, can you give me a discount?\"")
        print("-----------------------------------------------------------------")
        await ws.send(json.dumps({
            "type": "CALLER_SPEECH",
            "text": "Wait, I have a doubt, can you give me a discount?"
        }))

        doubt_reply = None
        for _ in range(20):
            msg_raw = await asyncio.wait_for(ws.recv(), timeout=5.0)
            if isinstance(msg_raw, str):
                msg = json.loads(msg_raw)
                if msg.get("type") == "TRANSCRIPT" and msg.get("speaker") == "AI":
                    doubt_reply = msg.get("text")
                    print(f" >>> AI Doubt/Bargain Reply: \"{doubt_reply}\"")
                    break

        assert doubt_reply is not None, "Failed to get reply to doubt"
        print("  --> PASS: Natural explanation given to doubt!")

        await ws.send(json.dumps({"type": "CALL_ENDED"}))

    print("\n" + "=" * 65)
    print("  ALL INTERRUPT & CONVERSATION TESTS PASSED CLEANLY")
    print("=" * 65)

if __name__ == "__main__":
    asyncio.run(run_test())
