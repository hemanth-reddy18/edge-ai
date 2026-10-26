import React from 'react';
import { VoiceCallLab } from './components/VoiceCallLab.tsx';

export const App: React.FC = () => {
  return (
    <div style={{
      minHeight: '100vh',
      display: 'flex',
      flexDirection: 'column',
      justifyContent: 'space-between',
      padding: '24px 16px',
      background: 'radial-gradient(circle at 50% 0%, #1e293b 0%, #090d16 65%)'
    }}>
      <header style={{ textAlign: 'center', marginBottom: '8px' }}>
        <div style={{ display: 'inline-flex', alignItems: 'center', gap: '8px', fontSize: '13px', color: '#38bdf8', fontWeight: 600 }}>
          <span>● REAL ON-DEVICE INFERENCE &bull; NO CLOUD LLM</span>
        </div>
      </header>

      <main style={{ flex: 1, display: 'flex', justifyContent: 'center', alignItems: 'center' }}>
        <VoiceCallLab />
      </main>

      <footer style={{ textAlign: 'center', marginTop: '24px', fontSize: '12px', color: '#64748b' }}>
        Edge AI Receptionist &bull; Physical Handset: Vivo V2509 (Android 16 API 36) &bull; AudioSocket Relay
      </footer>
    </div>
  );
};
