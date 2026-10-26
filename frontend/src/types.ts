export type CallState =
  | 'IDLE'
  | 'REQUESTING_MIC'
  | 'CONNECTING_EDGE'
  | 'WAITING_DEVICE'
  | 'CONNECTED'
  | 'LISTENING'
  | 'TRANSCRIBING'
  | 'THINKING'
  | 'SPEAKING'
  | 'MUTED'
  | 'ENDED'
  | 'ERROR';

export interface TranscriptTurn {
  id: string;
  speaker: 'CALLER' | 'AI';
  text: string;
  language?: string;
  timestamp: number;
}

export interface DiagnosticsData {
  backendUrl: string;
  backendHealthy: boolean;
  deviceSerial: string;
  deviceModel: string;
  deviceOnline: boolean;
  modelsReady: boolean;
  modelsDescription: string;
  sampleRate: number;
  format: string;
  bytesSent: number;
  bytesReceived: number;
  activeSessionId: string | null;
  latencyMs: number;
}

export interface LanguageOption {
  id: string;
  label: string;
  nativeLabel: string;
}

export const SUPPORTED_LANGUAGES: LanguageOption[] = [
  { id: 'auto', label: 'Auto-Detect', nativeLabel: 'Multilingual' },
  { id: 'en-IN', label: 'English', nativeLabel: 'Indian English' },
  { id: 'hi-IN', label: 'Hindi', nativeLabel: 'हिन्दी' },
  { id: 'te-IN', label: 'Telugu', nativeLabel: 'తెలుగు' },
  { id: 'ta-IN', label: 'Tamil', nativeLabel: 'தமிழ்' },
  { id: 'ml-IN', label: 'Malayalam', nativeLabel: 'മലയാളം' },
  { id: 'kn-IN', label: 'Kannada', nativeLabel: 'ಕನ್ನಡ' }
];

export const BUSINESS_PROFILES = [
  { id: 'Real Estate', label: 'Real Estate', desc: 'Property listings, buy/rent inquiries, agent callbacks' },
  { id: 'Supermarket', label: 'Supermarket', desc: 'Produce, grocery inventory, timings, delivery slots' },
  { id: 'Medical Shop / Pharmacy', label: 'Pharmacy', desc: 'Prescription medicines, OTC drugs, health items' },
  { id: 'Customer Support', label: 'Customer Support', desc: 'Order tracking, service tickets, issue triage' },
  { id: 'Custom Business', label: 'Custom Business', desc: 'Configurable business prompts and knowledge' }
];
