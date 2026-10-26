#!/usr/bin/env python3
"""
Edge Asterisk AudioSocket & Gateway Media Bridge
Connects Asterisk PBX AudioSocket channel directly to Edge's on-device AI Receptionist
via authenticated WebSocket gateway.
"""

import asyncio
import struct
import json
import requests
import time
import subprocess
import websockets
import logging

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("audiosocket_bridge")

BACKEND_HTTP = "http://127.0.0.1:8000"
BACKEND_WS = "ws://127.0.0.1:8000"
DEVICE_SERIAL = "10MF86F8HG0001B"
LISTEN_HOST = "0.0.0.0"
LISTEN_PORT = 9099

# Asterisk AudioSocket Message Types
TYPE_HANGUP = 0x00
TYPE_UUID = 0x01
TYPE_SILENCE = 0x02
TYPE_AUDIO = 0x10
TYPE_ERROR = 0xFF

def resample_8k_to_16k_mono(pcm8k: bytes) -> bytes:
    """Up-samples 8kHz 16-bit linear PCM to 16kHz 16-bit linear PCM via linear interpolation."""
    sample_count = len(pcm8k) // 2
    if sample_count <= 0:
        return b""
    
    samples = struct.unpack(f"<{sample_count}h", pcm8k)
    out_samples = []
    for i in range(sample_count):
        s1 = samples[i]
        s2 = samples[i + 1] if i + 1 < sample_count else s1
        out_samples.append(s1)
        out_samples.append((s1 + s2) // 2)
    
    return struct.pack(f"<{len(out_samples)}h", *out_samples)

def resample_16k_to_8k_mono(pcm16k: bytes) -> bytes:
    """Down-samples 16kHz 16-bit linear PCM to 8kHz 16-bit linear PCM by averaging adjacent pairs."""
    sample_count = len(pcm16k) // 2
    if sample_count <= 1:
        return b""
    
    samples = struct.unpack(f"<{sample_count}h", pcm16k)
    out_samples = []
    for i in range(0, sample_count - 1, 2):
        avg = (samples[i] + samples[i + 1]) // 2
        out_samples.append(avg)
    
    return struct.pack(f"<{len(out_samples)}h", *out_samples)

async def handle_audiosocket_client(reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
    peer = writer.get_extra_info("peername")
    logger.info(f"Incoming AudioSocket connection from Asterisk PBX: {peer}")

    ws_gateway = None
    call_id = f"sip_call_{int(time.time())}_{peer[1]}"
    caller_num = "SIP 1001 (Softphone)"
    called_did = "1000 (Edge AI)"

    try:
        # 1. Read UUID handshake message from Asterisk
        header = await reader.readexactly(3)
        msg_type, length = struct.unpack("!BH", header)
        if msg_type == TYPE_UUID and length > 0:
            uuid_bytes = await reader.readexactly(length)
            uuid_str = uuid_bytes.hex()
            logger.info(f"AudioSocket Handshake: Call UUID {uuid_str}")
        else:
            logger.warning(f"Unexpected initial AudioSocket message type: {msg_type}")

        # 2. Register inbound call with Edge backend control plane
        logger.info(f"Registering inbound carrier call {call_id} with Edge control plane...")
        res = requests.post(
            f"{BACKEND_HTTP}/v1/telephony/webhook/inbound-call",
            json={
                "call_id": call_id,
                "caller_number": caller_num,
                "called_did": called_did,
                "codec": "pcm_s16le"
            },
            timeout=5.0
        )
        if res.status_code != 200:
            logger.error(f"Backend rejected call registration: {res.text}")
            writer.close()
            return

        call_data = res.json()
        token = call_data["pairing_token"]
        ws_url = f"{BACKEND_WS}/ws/telephony/gateway-media?call_id={call_id}&token={token}"

        # 3. Ensure phone is awake and trigger CarrierCallReceiver on Vivo phone
        logger.info(f"Waking Vivo device and triggering CarrierCallReceiver for call {call_id}...")
        wake_cmd = f"adb -s {DEVICE_SERIAL} shell 'input keyevent KEYCODE_WAKEUP && am start -n com.edge.reception/.MainActivity'"
        subprocess.run(wake_cmd, shell=True, check=False)
        await asyncio.sleep(0.3)

        broadcast_cmd = (
            f"adb -s {DEVICE_SERIAL} shell 'am broadcast -a com.edge.reception.START_CARRIER_CALL "
            f"-n com.edge.reception/.telephony.CarrierCallReceiver -f 0x10000000 "
            f"--es call_id {call_id} --es token {token} --ez is_test_call true'"
        )
        subprocess.run(broadcast_cmd, shell=True, check=False)

        # 4. Connect to Edge Gateway WebSocket
        logger.info(f"Connecting to Gateway Media WebSocket: {ws_url}")
        ws_gateway = await websockets.connect(ws_url)
        logger.info("Gateway Media WebSocket connected. Bi-directional audio bridge active.")

        # Task to forward audio from Edge back to Asterisk AudioSocket
        async def edge_to_asterisk():
            try:
                async for message in ws_gateway:
                    if isinstance(message, bytes):
                        # 16kHz PCM from Edge -> Downsample to 8kHz linear PCM for Asterisk AudioSocket
                        pcm8k = resample_16k_to_8k_mono(message)
                        if pcm8k:
                            frame = bytes([TYPE_AUDIO]) + struct.pack("!H", len(pcm8k)) + pcm8k
                            writer.write(frame)
                            await writer.drain()
                    elif isinstance(message, str):
                        data = json.loads(message)
                        msg_type = data.get("type")
                        logger.info(f"[Edge -> PBX Control]: {msg_type} - {data.get('text', '')}")
                        if msg_type == "CALL_ENDED":
                            # Signal hangup to Asterisk
                            writer.write(bytes([TYPE_HANGUP, 0x00, 0x00]))
                            await writer.drain()
                            break
            except Exception as e:
                logger.debug(f"edge_to_asterisk stream closed: {e}")

        asyncio.create_task(edge_to_asterisk())

        # Main loop: Read AudioSocket packets from Asterisk and forward to Edge
        while True:
            header = await reader.readexactly(3)
            msg_type, length = struct.unpack("!BH", header)

            if msg_type == TYPE_HANGUP:
                logger.info("Asterisk AudioSocket signaled HANGUP.")
                if ws_gateway:
                    try:
                        await ws_gateway.send(json.dumps({"type": "CALL_ENDED"}))
                    except Exception:
                        pass
                break

            elif msg_type == TYPE_SILENCE:
                logger.info("Asterisk AudioSocket signaled SILENCE / SPEECH_DONE.")
                if ws_gateway:
                    try:
                        await ws_gateway.send(json.dumps({"type": "CALLER_SPEECH_DONE"}))
                    except Exception:
                        pass

            elif msg_type == TYPE_AUDIO and length > 0:
                pcm_data = await reader.readexactly(length)
                # Resample 8kHz caller audio from Asterisk to 16kHz mono PCM for Edge AI pipeline
                pcm16k = resample_8k_to_16k_mono(pcm_data)
                if ws_gateway:
                    try:
                        await ws_gateway.send(pcm16k)
                    except Exception:
                        break

            elif length > 0:
                await reader.readexactly(length)

    except asyncio.IncompleteReadError:
        logger.info("Asterisk closed AudioSocket connection.")
    except Exception as e:
        logger.error(f"Error in AudioSocket bridge: {e}", exc_info=True)
    finally:
        if ws_gateway:
            try:
                await ws_gateway.send(json.dumps({"type": "CALL_ENDED"}))
                await ws_gateway.close()
            except Exception:
                pass
        writer.close()
        try:
            await writer.wait_closed()
        except Exception:
            pass
        logger.info(f"AudioSocket session for {call_id} closed cleanly.")

async def main():
    server = await asyncio.start_server(handle_audiosocket_client, LISTEN_HOST, LISTEN_PORT)
    addrs = ", ".join(str(sock.getsockname()) for sock in server.sockets)
    logger.info(f"Asterisk AudioSocket Bridge server listening on {addrs}")
    async with server:
        await server.serve_forever()

if __name__ == "__main__":
    asyncio.run(main())
