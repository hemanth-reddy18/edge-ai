import React, { useState } from 'react';
import { DiagnosticsData } from '../types.ts';

interface Props {
  diag: DiagnosticsData;
}

export const DiagnosticsPanel: React.FC<Props> = ({ diag }) => {
  const [isExpanded, setIsExpanded] = useState(false);

  return (
    <div style={{
      background: 'rgba(15, 23, 42, 0.7)',
      border: '1px solid #1e293b',
      borderRadius: '12px',
      overflow: 'hidden',
      fontSize: '12px'
    }}>
      <button
        onClick={() => setIsExpanded(!isExpanded)}
        style={{
          width: '100%',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          padding: '10px 16px',
          background: 'transparent',
          border: 'none',
          color: '#94a3b8',
          fontSize: '12px',
          fontWeight: 600
        }}
      >
        <span style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <span style={{
            display: 'inline-block',
            width: '8px',
            height: '8px',
            borderRadius: '50%',
            backgroundColor: diag.backendHealthy && diag.deviceOnline ? '#10b981' : '#f59e0b'
          }} />
          Runtime Diagnostics &amp; Hardware Pipeline
        </span>
        <span style={{ fontSize: '11px', color: '#64748b' }}>
          {isExpanded ? 'Hide ▲' : 'Show Details ▼'}
        </span>
      </button>

      {isExpanded && (
        <div style={{
          padding: '12px 16px',
          borderTop: '1px solid #1e293b',
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
          gap: '12px',
          color: '#cbd5e1',
          lineHeight: 1.6
        }}>
          <div>
            <div style={{ color: '#64748b', fontSize: '11px' }}>Backend API &amp; Gateway</div>
            <div style={{ fontWeight: 600 }}>{diag.backendUrl}</div>
            <div style={{ color: diag.backendHealthy ? '#34d399' : '#ef4444', fontSize: '11px' }}>
              {diag.backendHealthy ? '● Uvicorn Active (Port 8000)' : '○ Unreachable'}
            </div>
          </div>

          <div>
            <div style={{ color: '#64748b', fontSize: '11px' }}>Connected Android Device</div>
            <div style={{ fontWeight: 600 }}>{diag.deviceModel} ({diag.deviceSerial})</div>
            <div style={{ color: diag.deviceOnline ? '#34d399' : '#f59e0b', fontSize: '11px' }}>
              {diag.deviceOnline ? '● ADB Reverse tcp:8000 Active' : '○ Checking connection...'}
            </div>
          </div>

          <div>
            <div style={{ color: '#64748b', fontSize: '11px' }}>On-Device AI Models</div>
            <div style={{ fontWeight: 600 }}>Silero VAD + Whisper + Qwen3</div>
            <div style={{ color: '#38bdf8', fontSize: '11px' }}>
              {diag.modelsDescription}
            </div>
          </div>

          <div>
            <div style={{ color: '#64748b', fontSize: '11px' }}>Audio Pipeline Protocol</div>
            <div style={{ fontWeight: 600 }}>{diag.sampleRate} Hz Mono S16LE</div>
            <div style={{ color: '#94a3b8', fontSize: '11px' }}>
              WebSocket binary frames (20ms frames)
            </div>
          </div>

          <div>
            <div style={{ color: '#64748b', fontSize: '11px' }}>Live Session Audio Streamed</div>
            <div style={{ fontWeight: 600 }}>
              ↑ {(diag.bytesSent / 1024).toFixed(1)} KB &nbsp;|&nbsp; ↓ {(diag.bytesReceived / 1024).toFixed(1)} KB
            </div>
            <div style={{ color: '#94a3b8', fontSize: '11px' }}>
              Session: <code>{diag.activeSessionId ? diag.activeSessionId.substring(0, 16) + '...' : 'None'}</code>
            </div>
          </div>

          <div>
            <div style={{ color: '#64748b', fontSize: '11px' }}>PSTN Telephony Route Notice</div>
            <div style={{ color: '#f59e0b', fontSize: '11px', lineHeight: 1.4 }}>
              Local browser voice test over USB relay. Cellular call forwarding via Airtel requires public Exotel DID (separate later phase).
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
