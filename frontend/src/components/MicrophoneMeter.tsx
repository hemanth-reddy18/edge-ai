import React from 'react';

interface Props {
  volume: number; // 0 to 100
  isMuted: boolean;
  isAiSpeaking: boolean;
  isActive: boolean;
}

export const MicrophoneMeter: React.FC<Props> = ({ volume, isMuted, isAiSpeaking, isActive }) => {
  const getStatusText = () => {
    if (!isActive) return 'Microphone inactive';
    if (isMuted) return 'Microphone muted';
    if (isAiSpeaking) return 'Gated (AI speaking)';
    if (volume > 15) return 'Voice detected';
    return 'Listening...';
  };

  const getBarColor = () => {
    if (isMuted) return '#ef4444';
    if (isAiSpeaking) return '#64748b';
    if (volume > 50) return '#38bdf8';
    return '#10b981';
  };

  return (
    <div style={{
      background: 'rgba(17, 24, 39, 0.7)',
      border: '1px solid #1f293d',
      borderRadius: '12px',
      padding: '12px 16px',
      display: 'flex',
      flexDirection: 'column',
      gap: '8px',
      backdropFilter: 'blur(8px)'
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: '12px' }}>
        <span style={{ color: '#94a3b8', display: 'flex', alignItems: 'center', gap: '6px' }}>
          <span style={{
            display: 'inline-block',
            width: '8px',
            height: '8px',
            borderRadius: '50%',
            backgroundColor: !isActive ? '#475569' : isMuted ? '#ef4444' : isAiSpeaking ? '#f59e0b' : '#10b981'
          }} />
          Microphone Activity
        </span>
        <span style={{
          fontSize: '11px',
          fontWeight: 600,
          color: isMuted ? '#fca5a5' : '#cbd5e1',
          textTransform: 'uppercase',
          letterSpacing: '0.04em'
        }}>
          {getStatusText()}
        </span>
      </div>

      <div style={{
        height: '8px',
        backgroundColor: '#0f172a',
        borderRadius: '999px',
        overflow: 'hidden',
        position: 'relative'
      }}>
        <div style={{
          height: '100%',
          width: `${isActive && !isMuted && !isAiSpeaking ? volume : 0}%`,
          backgroundColor: getBarColor(),
          borderRadius: '999px',
          transition: 'width 0.08s ease-out, background-color 0.2s ease',
          boxShadow: volume > 20 && !isMuted && !isAiSpeaking ? '0 0 10px rgba(56, 189, 248, 0.5)' : 'none'
        }} />
      </div>
    </div>
  );
};
