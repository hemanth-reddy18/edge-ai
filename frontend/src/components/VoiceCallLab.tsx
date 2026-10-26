import React, { useState, useEffect, useRef } from 'react';
import {
  CallState,
  TranscriptTurn,
  DiagnosticsData,
  SUPPORTED_LANGUAGES,
  BUSINESS_PROFILES
} from '../types.ts';
import { AudioEngine } from '../audio/AudioEngine.ts';
import { MicrophoneMeter } from './MicrophoneMeter.tsx';
import { LiveTranscript } from './LiveTranscript.tsx';
import { DiagnosticsPanel } from './DiagnosticsPanel.tsx';

export const VoiceCallLab: React.FC = () => {
  const [callState, setCallState] = useState<CallState>('IDLE');
  const [selectedLanguage, setSelectedLanguage] = useState<string>('auto');
  const [selectedPersona, setSelectedPersona] = useState<string>('SOUTH_INDIAN_FEMALE');
  const [selectedBusinessType, setSelectedBusinessType] = useState<string>('Real Estate');

  const [isMuted, setIsMuted] = useState<boolean>(false);
  const [isAiSpeaking, setIsAiSpeaking] = useState<boolean>(false);
  const [isAiThinking, setIsAiThinking] = useState<boolean>(false);
  const [micVolume, setMicVolume] = useState<number>(0);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const [turns, setTurns] = useState<TranscriptTurn[]>([]);
  const [inputQuestion, setInputQuestion] = useState<string>('');
  const recognitionRef = useRef<any>(null);
  const pcmReceivedForCurrentTurnRef = useRef<boolean>(false);
  const [diagnostics, setDiagnostics] = useState<DiagnosticsData>({
    backendUrl: 'http://127.0.0.1:8000',
    backendHealthy: false,
    deviceSerial: '10MF86F8HG0001B',
    deviceModel: 'Vivo V2509 (Android 16)',
    deviceOnline: true,
    modelsReady: true,
    modelsDescription: 'Silero VAD (2.3MB) + Whisper Base (59.7MB) + Qwen3 (429MB) + Google TTS',
    sampleRate: 16000,
    format: 'pcm_s16le (Mono)',
    bytesSent: 0,
    bytesReceived: 0,
    activeSessionId: null,
    latencyMs: 0
  });

  const audioEngineRef = useRef<AudioEngine | null>(null);
  const wsRef = useRef<WebSocket | null>(null);
  const speechDoneTimerRef = useRef<number | null>(null);
  const hasSpokenInTurnRef = useRef<boolean>(false);
  const hasEverReceivedPcmRef = useRef<boolean>(false);

  const handleInterrupt = () => {
    console.log('⚡ Caller interruption (barge-in) triggered! Halting AI playback immediately.');
    if ('speechSynthesis' in window) {
      window.speechSynthesis.cancel();
    }
    if (audioEngineRef.current) {
      audioEngineRef.current.stopAllPlayback();
    }
    setIsAiSpeaking(false);
    setIsAiThinking(false);
    setCallState('LISTENING');

    if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
      wsRef.current.send(JSON.stringify({ type: 'INTERRUPT' }));
    }
  };

  // Poll backend health on mount
  useEffect(() => {
    const checkBackend = async () => {
      try {
        const res = await fetch('/v1/health');
        if (res.ok) {
          const data = await res.json();
          setDiagnostics(prev => ({
            ...prev,
            backendHealthy: data.status === 'ok'
          }));
        }
      } catch (e) {
        setDiagnostics(prev => ({ ...prev, backendHealthy: false }));
      }
    };
    checkBackend();
    const interval = setInterval(checkBackend, 5000);
    return () => clearInterval(interval);
  }, []);

  // Initialize AudioEngine
  useEffect(() => {
    const engine = new AudioEngine();
    audioEngineRef.current = engine;

    engine.onBargeIn = () => {
      handleInterrupt();
    };

    engine.onVolumeChange = (vol) => {
      setMicVolume(vol);

      // Simple voice activity detection in browser: if user was speaking and stopped for 800ms, trigger speech done
      if (vol > 15) {
        hasSpokenInTurnRef.current = true;
        if (speechDoneTimerRef.current) {
          clearTimeout(speechDoneTimerRef.current);
          speechDoneTimerRef.current = null;
        }
      } else if (hasSpokenInTurnRef.current && !speechDoneTimerRef.current) {
        speechDoneTimerRef.current = window.setTimeout(() => {
          if (hasSpokenInTurnRef.current && wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
            console.log('Browser speech pause detected -> signaling CALLER_SPEECH_DONE');
            wsRef.current.send(JSON.stringify({ type: 'CALLER_SPEECH_DONE' }));
            hasSpokenInTurnRef.current = false;
            setIsAiThinking(true);
            setCallState('TRANSCRIBING');
          }
          speechDoneTimerRef.current = null;
        }, 800);
      }
    };

    engine.onPcmChunk = (chunk) => {
      if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
        wsRef.current.send(chunk);
        setDiagnostics(prev => ({ ...prev, bytesSent: prev.bytesSent + chunk.byteLength }));
      }
    };

    engine.onAiSpeakingChange = (speaking) => {
      setIsAiSpeaking(speaking);
      if (speaking) {
        setCallState('SPEAKING');
        setIsAiThinking(false);
      } else {
        setCallState('LISTENING');
      }
    };

    return () => {
      engine.stop();
    };
  }, []);

  const sendCallerSpeech = (text: string) => {
    if (!text || !text.trim()) return;
    const cleanText = text.trim();
    handleInterrupt(); // Immediately stop any speaking AI

    if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
      pcmReceivedForCurrentTurnRef.current = false;
      wsRef.current.send(JSON.stringify({
        type: 'CALLER_SPEECH',
        text: cleanText
      }));
      setTurns(prev => [
        ...prev,
        {
          id: 'turn_user_' + Date.now(),
          speaker: 'CALLER',
          text: cleanText,
          timestamp: Date.now()
        }
      ]);
      setIsAiThinking(true);
      setCallState('THINKING');
    }
  };

  const startSpeechRecognition = () => {
    const SpeechRec = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (!SpeechRec) {
      console.warn('SpeechRecognition API not available in browser');
      return;
    }
    try {
      const rec = new SpeechRec();
      rec.continuous = true;
      rec.interimResults = true;
      rec.lang = 'en-IN';
      rec.onresult = (evt: any) => {
        const lastIdx = evt.results.length - 1;
        const res = evt.results[lastIdx];
        const text = res[0]?.transcript;

        // Barge-in: if user starts speaking while AI is speaking, cut off AI immediately!
        if (text && text.trim().length > 1) {
          if (isAiSpeaking || audioEngineRef.current?.getIsAiSpeaking?.()) {
            handleInterrupt();
          }
        }

        if (res.isFinal && text && text.trim().length > 1) {
          sendCallerSpeech(text.trim());
        }
      };
      rec.onerror = (e: any) => {
        console.warn('Speech recognition status:', e.error);
      };
      rec.start();
      recognitionRef.current = rec;
    } catch (e) {
      console.warn('Could not start speech recognition:', e);
    }
  };

  const stopSpeechRecognition = () => {
    try {
      if (recognitionRef.current) {
        recognitionRef.current.stop();
        recognitionRef.current = null;
      }
    } catch (_) {}
  };

  const handleStartCall = async () => {
    setErrorMessage(null);
    setTurns([]);
    hasSpokenInTurnRef.current = false;
    hasEverReceivedPcmRef.current = false;
    pcmReceivedForCurrentTurnRef.current = false;

    try {
      // 1. Request microphone permission
      setCallState('REQUESTING_MIC');
      if (audioEngineRef.current) {
        await audioEngineRef.current.startMicrophone();
      }
      startSpeechRecognition();

      // 2. Request voice session from FastAPI backend (auto-triggers Android app via ADB)
      setCallState('CONNECTING_EDGE');
      const sessionRes = await fetch('/v1/voice-test/session', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({})
      });

      if (!sessionRes.ok) {
        throw new Error(`Failed to create voice session: HTTP ${sessionRes.status}`);
      }

      const sessionData = await sessionRes.json();
      const { session_id, pairing_token, ws_caller_url } = sessionData;

      setDiagnostics(prev => ({
        ...prev,
        activeSessionId: session_id,
        bytesSent: 0,
        bytesReceived: 0
      }));

      // 3. Connect to WebSocket
      setCallState('WAITING_DEVICE');
      const wsUrl = ws_caller_url || `ws://${window.location.host}/ws/test-caller?session_id=${session_id}&token=${pairing_token}`;
      const ws = new WebSocket(wsUrl);
      ws.binaryType = 'arraybuffer';
      wsRef.current = ws;

      ws.onopen = () => {
        console.log('Connected to ws_test_caller');
        // Send START message with dynamic language, voice persona, and business type
        ws.send(JSON.stringify({
          type: 'START',
          session_id,
          language: selectedLanguage,
          voice_persona: selectedPersona,
          business_type: selectedBusinessType
        }));
      };

      ws.onmessage = (event) => {
        if (typeof event.data === 'string') {
          try {
            const msg = JSON.parse(event.data);
            handleControlMessage(msg);
          } catch (e) {
            console.error('Failed to parse WS text message:', e);
          }
        } else if (event.data instanceof ArrayBuffer) {
          hasEverReceivedPcmRef.current = true;
          pcmReceivedForCurrentTurnRef.current = true;
          if ('speechSynthesis' in window) {
            window.speechSynthesis.cancel();
          }
          const byteLength = event.data.byteLength;
          setDiagnostics(prev => ({ ...prev, bytesReceived: prev.bytesReceived + byteLength }));
          if (audioEngineRef.current) {
            audioEngineRef.current.playIncomingPcmChunk(event.data);
          }
        }
      };

      ws.onerror = (err) => {
        console.error('WebSocket error:', err);
        setErrorMessage('WebSocket connection error. Ensure FastAPI backend is running on port 8000.');
        setCallState('ERROR');
      };

      ws.onclose = () => {
        console.log('WebSocket closed');
        if (callState !== 'ENDED') {
          setCallState('ENDED');
        }
      };

    } catch (err: unknown) {
      console.error('Call initialization error:', err);
      const msg = err instanceof Error ? err.message : 'Failed to connect call';
      setErrorMessage(msg);
      setCallState('ERROR');
      if (audioEngineRef.current) {
        audioEngineRef.current.stop();
      }
    }
  };

  const handleControlMessage = (msg: { type: string; [key: string]: unknown }) => {
    console.log('WS Control Message:', msg.type, msg);

    switch (msg.type) {
      case 'PEER_CONNECTED':
        setCallState('CONNECTED');
        break;

      case 'GREETING_START':
        setCallState('SPEAKING');
        setIsAiSpeaking(true);
        if (typeof msg.text === 'string') {
          const lang = typeof msg.language === 'string' ? msg.language : undefined;
          setTurns(prev => [
            ...prev,
            {
              id: 'turn_greeting_' + Date.now(),
              speaker: 'AI',
              text: msg.text as string,
              language: lang,
              timestamp: Date.now()
            }
          ]);
        }
        break;

      case 'AI_SPEAKING_START':
        setIsAiSpeaking(true);
        setIsAiThinking(false);
        setCallState('SPEAKING');
        break;

      case 'AI_SPEAKING_STOP':
      case 'GREETING_COMPLETED':
        setIsAiSpeaking(false);
        setIsAiThinking(false);
        setCallState('LISTENING');
        break;

      case 'TRANSCRIPT': {
        const speaker = msg.speaker === 'Caller' ? 'CALLER' : 'AI';
        const text = typeof msg.text === 'string' ? msg.text : '';
        const lang = typeof msg.language === 'string' ? msg.language : undefined;
        if (text) {
          setTurns(prev => [
            ...prev,
            {
              id: 'turn_' + Date.now() + '_' + Math.random().toString(36).substring(2, 6),
              speaker,
              text,
              language: lang,
              timestamp: Date.now()
            }
          ]);

          // Web Speech fallback ONLY if phone PCM is not streaming to prevent duplicate conflicting voices!
          if (speaker === 'AI' && !hasEverReceivedPcmRef.current && ('speechSynthesis' in window)) {
            window.speechSynthesis.cancel();
            const utter = new SpeechSynthesisUtterance(text);
            utter.rate = 1.0;
            const voices = window.speechSynthesis.getVoices();
            const inVoice = voices.find(v => v.lang.includes('IN')) || voices.find(v => v.lang.startsWith('en'));
            if (inVoice) utter.voice = inVoice;
            utter.onstart = () => {
              setIsAiSpeaking(true);
              setCallState('SPEAKING');
            };
            utter.onend = () => {
              setIsAiSpeaking(false);
              setCallState('LISTENING');
            };
            window.speechSynthesis.speak(utter);
          }
        }
        if (speaker === 'CALLER') {
          setIsAiThinking(true);
          setCallState('THINKING');
        }
        break;
      }

      case 'CALL_ENDED':
        handleEndCall();
        break;
    }
  };

  const handleToggleMute = () => {
    if (!audioEngineRef.current) return;
    const nextMuted = !isMuted;
    audioEngineRef.current.setMuted(nextMuted);
    setIsMuted(nextMuted);
    if (nextMuted) {
      setCallState('MUTED');
    } else {
      setCallState(isAiSpeaking ? 'SPEAKING' : 'LISTENING');
    }
  };

  const handleEndCall = () => {
    stopSpeechRecognition();
    if ('speechSynthesis' in window) {
      window.speechSynthesis.cancel();
    }
    if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
      wsRef.current.send(JSON.stringify({ type: 'CALL_ENDED' }));
      wsRef.current.close();
      wsRef.current = null;
    }

    if (audioEngineRef.current) {
      audioEngineRef.current.stop();
    }

    // Inform backend to end device session
    fetch('/v1/voice-test/end-device', { method: 'POST' }).catch(() => {});

    setIsMuted(false);
    setIsAiSpeaking(false);
    setIsAiThinking(false);
    setCallState('ENDED');

    setTimeout(() => {
      setCallState('IDLE');
    }, 2000);
  };

  const isCallActive = callState !== 'IDLE' && callState !== 'ENDED' && callState !== 'ERROR';

  const getStateBadge = () => {
    switch (callState) {
      case 'IDLE':
        return { label: 'Ready to Call', color: '#10b981', bg: 'rgba(16, 185, 129, 0.15)' };
      case 'REQUESTING_MIC':
        return { label: 'Microphone Permission...', color: '#38bdf8', bg: 'rgba(56, 189, 248, 0.15)' };
      case 'CONNECTING_EDGE':
      case 'WAITING_DEVICE':
        return { label: 'Connecting to Vivo AI...', color: '#f59e0b', bg: 'rgba(245, 158, 11, 0.15)' };
      case 'CONNECTED':
        return { label: 'Connected', color: '#10b981', bg: 'rgba(16, 185, 129, 0.2)' };
      case 'LISTENING':
        return { label: 'Listening to your speech', color: '#10b981', bg: 'rgba(16, 185, 129, 0.2)' };
      case 'TRANSCRIBING':
        return { label: 'On-device Whisper ASR...', color: '#38bdf8', bg: 'rgba(56, 189, 248, 0.2)' };
      case 'THINKING':
        return { label: 'On-device Qwen3 LLM...', color: '#a78bfa', bg: 'rgba(167, 139, 250, 0.2)' };
      case 'SPEAKING':
        return { label: 'Edge AI Speaking', color: '#38bdf8', bg: 'rgba(56, 189, 248, 0.25)' };
      case 'MUTED':
        return { label: 'Microphone Muted', color: '#ef4444', bg: 'rgba(239, 68, 68, 0.2)' };
      case 'ENDED':
        return { label: 'Session Ended', color: '#94a3b8', bg: 'rgba(148, 163, 184, 0.15)' };
      case 'ERROR':
        return { label: 'Connection Error', color: '#ef4444', bg: 'rgba(239, 68, 68, 0.2)' };
    }
  };

  const badge = getStateBadge();

  return (
    <div style={{
      maxWidth: '900px',
      margin: '0 auto',
      width: '100%',
      display: 'flex',
      flexDirection: 'column',
      gap: '20px'
    }}>
      {/* Header bar */}
      <div style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        background: 'rgba(17, 24, 39, 0.6)',
        border: '1px solid #1f293d',
        padding: '16px 20px',
        borderRadius: '16px',
        backdropFilter: 'blur(10px)'
      }}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
            <span style={{ fontSize: '20px', fontWeight: 700, color: '#f8fafc', letterSpacing: '-0.02em' }}>
              Edge AI Voice Lab
            </span>
            <span style={{
              fontSize: '11px',
              padding: '2px 8px',
              borderRadius: '999px',
              background: '#0369a1',
              color: '#38bdf8',
              fontWeight: 600
            }}>
              localhost:3000
            </span>
          </div>
          <div style={{ fontSize: '13px', color: '#94a3b8', marginTop: '2px' }}>
            Bidirectional Audio Relay &bull; On-Device Whisper + Qwen3-0.6B + TTS on Vivo V2509
          </div>
        </div>

        <div style={{
          display: 'flex',
          alignItems: 'center',
          gap: '8px',
          padding: '6px 14px',
          borderRadius: '999px',
          backgroundColor: badge.bg,
          color: badge.color,
          fontSize: '12px',
          fontWeight: 600
        }}>
          <span style={{
            display: 'inline-block',
            width: '8px',
            height: '8px',
            borderRadius: '50%',
            backgroundColor: badge.color,
            animation: isCallActive && !isMuted ? 'pulse-speaking 1.2s infinite' : 'none'
          }} />
          {badge.label}
        </div>
      </div>

      {/* Error alert if any */}
      {errorMessage && (
        <div style={{
          padding: '12px 16px',
          borderRadius: '12px',
          backgroundColor: 'rgba(239, 68, 68, 0.15)',
          border: '1px solid #ef4444',
          color: '#fca5a5',
          fontSize: '13px',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center'
        }}>
          <span>⚠️ {errorMessage}</span>
          <button
            onClick={() => setErrorMessage(null)}
            style={{ background: 'transparent', border: 'none', color: '#fca5a5', fontSize: '14px' }}
          >
            ✕
          </button>
        </div>
      )}

      {/* Main interactive call card */}
      <div style={{
        background: 'rgba(17, 24, 39, 0.8)',
        border: '1px solid #1f293d',
        borderRadius: '20px',
        padding: '28px 24px',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        gap: '24px',
        position: 'relative',
        boxShadow: isCallActive ? '0 0 40px rgba(56, 189, 248, 0.12)' : 'none',
        transition: 'box-shadow 0.3s ease'
      }}>
        {/* Configuration Selectors */}
        <div style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))',
          gap: '12px',
          width: '100%'
        }}>
          <div>
            <label style={{ fontSize: '11px', fontWeight: 600, color: '#94a3b8', textTransform: 'uppercase', marginBottom: '4px', display: 'block' }}>
              Spoken Language
            </label>
            <select
              value={selectedLanguage}
              onChange={(e) => setSelectedLanguage(e.target.value)}
              disabled={isCallActive}
              style={{
                width: '100%',
                padding: '9px 12px',
                borderRadius: '8px',
                backgroundColor: '#0f172a',
                border: '1px solid #334155',
                color: '#f8fafc',
                fontSize: '13px',
                outline: 'none'
              }}
            >
              {SUPPORTED_LANGUAGES.map(l => (
                <option key={l.id} value={l.id}>
                  {l.label} ({l.nativeLabel})
                </option>
              ))}
            </select>
          </div>

          <div>
            <label style={{ fontSize: '11px', fontWeight: 600, color: '#94a3b8', textTransform: 'uppercase', marginBottom: '4px', display: 'block' }}>
              Voice Persona
            </label>
            <select
              value={selectedPersona}
              onChange={(e) => setSelectedPersona(e.target.value)}
              disabled={isCallActive}
              style={{
                width: '100%',
                padding: '9px 12px',
                borderRadius: '8px',
                backgroundColor: '#0f172a',
                border: '1px solid #334155',
                color: '#f8fafc',
                fontSize: '13px',
                outline: 'none'
              }}
            >
              <option value="SOUTH_INDIAN_FEMALE">Female (South Indian)</option>
              <option value="SOUTH_INDIAN_MALE">Male (South Indian)</option>
            </select>
          </div>

          <div>
            <label style={{ fontSize: '11px', fontWeight: 600, color: '#94a3b8', textTransform: 'uppercase', marginBottom: '4px', display: 'block' }}>
              Business Profile / Script
            </label>
            <select
              value={selectedBusinessType}
              onChange={(e) => setSelectedBusinessType(e.target.value)}
              disabled={isCallActive}
              style={{
                width: '100%',
                padding: '9px 12px',
                borderRadius: '8px',
                backgroundColor: '#0f172a',
                border: '1px solid #334155',
                color: '#f8fafc',
                fontSize: '13px',
                outline: 'none'
              }}
            >
              {BUSINESS_PROFILES.map(b => (
                <option key={b.id} value={b.id}>
                  {b.label}
                </option>
              ))}
            </select>
          </div>
        </div>

        {/* Center Call Visualizer */}
        <div style={{
          width: '140px',
          height: '140px',
          borderRadius: '50%',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          backgroundColor: isAiSpeaking ? '#0369a1' : isCallActive ? (isMuted ? '#7f1d1d' : '#064e3b') : '#1e293b',
          border: `3px solid ${isAiSpeaking ? '#38bdf8' : isCallActive ? (isMuted ? '#ef4444' : '#10b981') : '#334155'}`,
          boxShadow: isAiSpeaking ? '0 0 35px rgba(56, 189, 248, 0.4)' : isCallActive && !isMuted ? '0 0 25px rgba(16, 185, 129, 0.3)' : 'none',
          transition: 'all 0.3s ease',
          position: 'relative'
        }}>
          <span style={{
            fontSize: '44px',
            userSelect: 'none',
            animation: isAiSpeaking ? 'pulse-speaking 1s infinite' : 'none'
          }}>
            {isAiSpeaking ? '🗣️' : isMuted ? '🔇' : isCallActive ? '🎙️' : '📞'}
          </span>
        </div>

        {/* Primary Call Controls */}
        <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
          {!isCallActive ? (
            <button
              onClick={handleStartCall}
              className="pulse-call"
              style={{
                backgroundColor: '#10b981',
                color: '#022c22',
                border: 'none',
                padding: '14px 36px',
                borderRadius: '999px',
                fontSize: '16px',
                fontWeight: 700,
                display: 'flex',
                alignItems: 'center',
                gap: '10px',
                cursor: 'pointer',
                boxShadow: '0 4px 14px rgba(16, 185, 129, 0.4)'
              }}
            >
              <span>📞</span> Call Edge AI
            </button>
          ) : (
            <>
              <button
                onClick={handleToggleMute}
                style={{
                  backgroundColor: isMuted ? '#ef4444' : '#1e293b',
                  color: isMuted ? '#ffffff' : '#f8fafc',
                  border: isMuted ? '1px solid #dc2626' : '1px solid #475569',
                  padding: '12px 24px',
                  borderRadius: '999px',
                  fontSize: '14px',
                  fontWeight: 600,
                  display: 'flex',
                  alignItems: 'center',
                  gap: '8px'
                }}
              >
                <span>{isMuted ? '🔇' : '🎙️'}</span>
                {isMuted ? 'Unmute Mic' : 'Mute Mic'}
              </button>

              <button
                onClick={handleEndCall}
                style={{
                  backgroundColor: '#ef4444',
                  color: '#ffffff',
                  border: 'none',
                  padding: '12px 28px',
                  borderRadius: '999px',
                  fontSize: '14px',
                  fontWeight: 700,
                  display: 'flex',
                  alignItems: 'center',
                  gap: '8px',
                  boxShadow: '0 4px 14px rgba(239, 68, 68, 0.4)'
                }}
              >
                <span>✕</span> End Call
              </button>
            </>
          )}
        </div>

        {/* Microphone Real-Time Activity Meter */}
        <div style={{ width: '100%', maxWidth: '500px' }}>
          <MicrophoneMeter
            volume={micVolume}
            isMuted={isMuted}
            isAiSpeaking={isAiSpeaking}
            isActive={isCallActive}
          />
        </div>
      </div>

      {/* Quick Questions & Real Estate Interactive Controls */}
      {isCallActive && (
        <div style={{
          width: '100%',
          background: 'rgba(15, 23, 42, 0.9)',
          border: '1px solid #334155',
          borderRadius: '16px',
          padding: '16px 20px',
          display: 'flex',
          flexDirection: 'column',
          gap: '12px'
        }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <span style={{ fontSize: '12px', fontWeight: 700, color: '#38bdf8', textTransform: 'uppercase', letterSpacing: '0.5px' }}>
              ✨ Speak or Tap to Ask Real Estate Advisor:
            </span>
            <span style={{ fontSize: '11px', color: '#94a3b8' }}>
              Microphone listens continuously • AI speaks back on laptop
            </span>
          </div>

          <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px' }}>
            {[
              { label: '🏢 Flats Inventory', text: 'Hi, what all flats and apartments do you have in Bangalore?' },
              { label: '📈 Sales Velocity', text: 'How is the sale going right now in your projects?' },
              { label: '🌳 Land & Villa Plots', text: 'What lands and gated villa plots do you have available?' },
              { label: '💰 Bangalore Land Prices', text: 'What are Bangalore land prices right now per square foot?' },
              { label: '🤝 Book VIP Site Visit', text: 'Can I schedule a VIP site visit for this weekend with cab pick-up?' }
            ].map((q, idx) => (
              <button
                key={idx}
                onClick={() => sendCallerSpeech(q.text)}
                style={{
                  background: 'rgba(30, 41, 59, 0.9)',
                  border: '1px solid #475569',
                  borderRadius: '20px',
                  padding: '7px 14px',
                  color: '#e2e8f0',
                  fontSize: '12px',
                  fontWeight: 600,
                  cursor: 'pointer',
                  transition: 'all 0.2s'
                }}
                onMouseOver={(e) => (e.currentTarget.style.borderColor = '#38bdf8')}
                onMouseOut={(e) => (e.currentTarget.style.borderColor = '#475569')}
              >
                {q.label}
              </button>
            ))}
          </div>

          <div style={{ display: 'flex', gap: '8px', width: '100%', marginTop: '4px' }}>
            <input
              type="text"
              placeholder="Ask anything about Bangalore flats, plots, prices, or sales..."
              value={inputQuestion}
              onChange={(e) => setInputQuestion(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && inputQuestion.trim()) {
                  sendCallerSpeech(inputQuestion);
                  setInputQuestion('');
                }
              }}
              style={{
                flex: 1,
                padding: '10px 14px',
                borderRadius: '8px',
                backgroundColor: '#020617',
                border: '1px solid #334155',
                color: '#f8fafc',
                fontSize: '13px',
                outline: 'none'
              }}
            />
            <button
              onClick={() => {
                if (inputQuestion.trim()) {
                  sendCallerSpeech(inputQuestion);
                  setInputQuestion('');
                }
              }}
              disabled={!inputQuestion.trim()}
              style={{
                padding: '10px 18px',
                borderRadius: '8px',
                backgroundColor: inputQuestion.trim() ? '#38bdf8' : '#334155',
                color: inputQuestion.trim() ? '#020617' : '#94a3b8',
                fontWeight: 700,
                border: 'none',
                cursor: inputQuestion.trim() ? 'pointer' : 'default'
              }}
            >
              Ask AI
            </button>
          </div>
        </div>
      )}

      {/* Live Transcript area */}
      <LiveTranscript
        turns={turns}
        isAiThinking={isAiThinking}
        isAiSpeaking={isAiSpeaking}
      />

      {/* Expandable Runtime Diagnostics Drawer */}
      <DiagnosticsPanel diag={diagnostics} />
    </div>
  );
};
