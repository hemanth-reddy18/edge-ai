import React, { useEffect, useRef } from 'react';
import { TranscriptTurn } from '../types.ts';

interface Props {
  turns: TranscriptTurn[];
  isAiThinking: boolean;
  isAiSpeaking: boolean;
}

export const LiveTranscript: React.FC<Props> = ({ turns, isAiThinking, isAiSpeaking }) => {
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [turns, isAiThinking, isAiSpeaking]);

  return (
    <div style={{
      background: 'rgba(17, 24, 39, 0.8)',
      border: '1px solid #1f293d',
      borderRadius: '16px',
      padding: '16px',
      display: 'flex',
      flexDirection: 'column',
      height: '320px',
      backdropFilter: 'blur(12px)'
    }}>
      <div style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        paddingBottom: '12px',
        borderBottom: '1px solid #1e293b',
        marginBottom: '12px'
      }}>
        <div style={{ fontSize: '13px', fontWeight: 600, color: '#94a3b8', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
          Live Conversation Transcript
        </div>
        <div style={{ fontSize: '12px', color: '#64748b' }}>
          {turns.length} turn{turns.length === 1 ? '' : 's'}
        </div>
      </div>

      <div
        ref={scrollRef}
        style={{
          flex: 1,
          overflowY: 'auto',
          display: 'flex',
          flexDirection: 'column',
          gap: '12px',
          paddingRight: '6px'
        }}
      >
        {turns.length === 0 ? (
          <div style={{
            margin: 'auto',
            textAlign: 'center',
            color: '#475569',
            fontSize: '13px',
            lineHeight: 1.6
          }}>
            Click <strong>Call</strong> to begin.<br />
            Edge AI on your phone will greet you automatically.
          </div>
        ) : (
          turns.map((turn) => {
            const isAi = turn.speaker === 'AI';
            return (
              <div
                key={turn.id}
                style={{
                  display: 'flex',
                  flexDirection: 'column',
                  alignItems: isAi ? 'flex-start' : 'flex-end',
                  gap: '4px'
                }}
              >
                <div style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: '6px',
                  fontSize: '11px',
                  color: isAi ? '#38bdf8' : '#94a3b8'
                }}>
                  <span style={{
                    fontWeight: 600,
                    textTransform: 'uppercase',
                    padding: '2px 6px',
                    borderRadius: '4px',
                    background: isAi ? 'rgba(56, 189, 248, 0.15)' : 'rgba(148, 163, 184, 0.15)',
                  }}>
                    {isAi ? 'Edge AI' : 'You'}
                  </span>
                  {turn.language && (
                    <span style={{ fontSize: '10px', color: '#64748b' }}>[{turn.language}]</span>
                  )}
                  <span style={{ color: '#475569' }}>
                    {new Date(turn.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
                  </span>
                </div>

                <div style={{
                  maxWidth: '85%',
                  padding: '10px 14px',
                  borderRadius: isAi ? '4px 14px 14px 14px' : '14px 4px 14px 14px',
                  backgroundColor: isAi ? '#1e293b' : '#0369a1',
                  color: '#f8fafc',
                  fontSize: '14px',
                  lineHeight: 1.5,
                  border: isAi ? '1px solid #334155' : '1px solid #0284c7',
                  boxShadow: '0 2px 8px rgba(0, 0, 0, 0.2)'
                }}>
                  {turn.text}
                </div>
              </div>
            );
          })
        )}

        {isAiThinking && (
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '6px 10px', color: '#38bdf8', fontSize: '12px' }}>
            <span style={{ display: 'inline-block', width: '6px', height: '6px', borderRadius: '50%', backgroundColor: '#38bdf8', animation: 'ripple 1s infinite' }} />
            On-device Whisper + Qwen3 processing speech...
          </div>
        )}

        {isAiSpeaking && (
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '6px 10px', color: '#10b981', fontSize: '12px' }}>
            <span style={{ display: 'inline-block', width: '6px', height: '6px', borderRadius: '50%', backgroundColor: '#10b981', animation: 'pulse-speaking 1s infinite' }} />
            Edge AI speaking (Streaming on-device TTS audio)...
          </div>
        )}
      </div>
    </div>
  );
};
