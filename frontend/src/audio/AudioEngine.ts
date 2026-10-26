export class AudioEngine {
  private micStream: MediaStream | null = null;
  private audioCtx: AudioContext | null = null;
  private analyser: AnalyserNode | null = null;
  private processor: ScriptProcessorNode | null = null;
  private micSource: MediaStreamAudioSourceNode | null = null;

  private isMuted: boolean = false;
  private isAiSpeaking: boolean = false;
  private nextPlayTime: number = 0;
  private animFrameId: number | null = null;
  private activeAudioSources: AudioBufferSourceNode[] = [];
  private bargeInFramesCount: number = 0;

  public onVolumeChange: (vol: number) => void = () => {};
  public onPcmChunk: (chunk: Uint8Array) => void = () => {};
  public onAiSpeakingChange: (isSpeaking: boolean) => void = () => {};
  public onBargeIn: () => void = () => {};

  public async startMicrophone(): Promise<void> {
    this.micStream = await navigator.mediaDevices.getUserMedia({
      audio: {
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true,
        channelCount: 1
      }
    });

    const AudioContextClass = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
    this.audioCtx = new AudioContextClass();
    if (this.audioCtx.state === 'suspended') {
      await this.audioCtx.resume();
    }

    this.analyser = this.audioCtx.createAnalyser();
    this.analyser.fftSize = 512;
    this.analyser.smoothingTimeConstant = 0.4;

    this.micSource = this.audioCtx.createMediaStreamSource(this.micStream);
    this.micSource.connect(this.analyser);

    // Buffer size 2048 or 4096 gives ~40ms chunks
    const bufferSize = 2048;
    this.processor = this.audioCtx.createScriptProcessor(bufferSize, 1, 1);

    const inputSampleRate = this.audioCtx.sampleRate;
    const targetSampleRate = 16000;

    this.processor.onaudioprocess = (e) => {
      // Gate audio only if user is explicitly muted
      if (this.isMuted) {
        return;
      }

      const inputData = e.inputBuffer.getChannelData(0);
      const resampled = this.downsampleTo16k(inputData, inputSampleRate, targetSampleRate);
      if (resampled && resampled.length > 0) {
        const pcm16 = this.floatTo16BitPCM(resampled);
        this.onPcmChunk(pcm16);
      }
    };

    this.micSource.connect(this.processor);
    this.processor.connect(this.audioCtx.destination);

    this.startVolumeMeter();
  }

  private startVolumeMeter() {
    const dataArray = new Uint8Array(this.analyser ? this.analyser.frequencyBinCount : 0);

    const updateMeter = () => {
      if (!this.analyser || this.isMuted) {
        this.onVolumeChange(0);
        this.bargeInFramesCount = 0;
      } else {
        this.analyser.getByteFrequencyData(dataArray);
        let sum = 0;
        for (let i = 0; i < dataArray.length; i++) {
          sum += dataArray[i];
        }
        const average = sum / dataArray.length;
        // Scale to 0-100 percentage
        const vol = Math.min(100, Math.round((average / 128) * 100));
        this.onVolumeChange(vol);

        // Instant Barge-In detection: If user speaks (volume > 22) while AI is speaking
        if (this.isAiSpeaking && vol > 22) {
          this.bargeInFramesCount++;
          if (this.bargeInFramesCount >= 3) { // ~50ms of sustained user voice
            console.log('⚡ Barge-in speech detected! Halting AI playback immediately.');
            this.bargeInFramesCount = 0;
            this.stopAllPlayback();
            this.onBargeIn();
          }
        } else {
          this.bargeInFramesCount = 0;
        }
      }
      this.animFrameId = requestAnimationFrame(updateMeter);
    };

    this.animFrameId = requestAnimationFrame(updateMeter);
  }

  private downsampleTo16k(buffer: Float32Array, inputRate: number, targetRate: number): Float32Array {
    if (inputRate === targetRate) {
      return buffer;
    }
    const ratio = inputRate / targetRate;
    const newLength = Math.round(buffer.length / ratio);
    const result = new Float32Array(newLength);
    let offsetResult = 0;
    let offsetBuffer = 0;

    while (offsetResult < result.length) {
      const nextOffsetBuffer = Math.round((offsetResult + 1) * ratio);
      let accum = 0;
      let count = 0;
      for (let i = offsetBuffer; i < nextOffsetBuffer && i < buffer.length; i++) {
        accum += buffer[i];
        count++;
      }
      result[offsetResult] = count > 0 ? accum / count : 0;
      offsetResult++;
      offsetBuffer = nextOffsetBuffer;
    }
    return result;
  }

  private floatTo16BitPCM(input: Float32Array): Uint8Array {
    const output = new Uint8Array(input.length * 2);
    const view = new DataView(output.buffer);
    for (let i = 0; i < input.length; i++) {
      let s = Math.max(-1, Math.min(1, input[i]));
      const int16 = s < 0 ? s * 0x8000 : s * 0x7fff;
      view.setInt16(i * 2, int16, true); // true = little-endian
    }
    return output;
  }

  public setMuted(muted: boolean): void {
    this.isMuted = muted;
    if (this.micStream) {
      this.micStream.getAudioTracks().forEach(track => {
        track.enabled = !muted;
      });
    }
  }

  public getIsMuted(): boolean {
    return this.isMuted;
  }

  public setAiSpeaking(speaking: boolean): void {
    if (this.isAiSpeaking !== speaking) {
      this.isAiSpeaking = speaking;
      this.onAiSpeakingChange(speaking);
    }
  }

  public getIsAiSpeaking(): boolean {
    return this.isAiSpeaking;
  }

  public playIncomingPcmChunk(pcmData: ArrayBuffer): void {
    if (!this.audioCtx) return;

    const sampleRate = 16000;
    const int16View = new Int16Array(pcmData);
    if (int16View.length === 0) return;

    const float32 = new Float32Array(int16View.length);
    for (let i = 0; i < int16View.length; i++) {
      float32[i] = int16View[i] / 32768.0;
    }

    const audioBuffer = this.audioCtx.createBuffer(1, float32.length, sampleRate);
    audioBuffer.getChannelData(0).set(float32);

    const source = this.audioCtx.createBufferSource();
    source.buffer = audioBuffer;
    source.connect(this.audioCtx.destination);

    const now = this.audioCtx.currentTime;
    if (this.nextPlayTime < now) {
      this.nextPlayTime = now + 0.02; // 20ms lead to avoid underflow
    }

    source.start(this.nextPlayTime);
    this.nextPlayTime += audioBuffer.duration;

    this.activeAudioSources.push(source);
    this.setAiSpeaking(true);

    source.onended = () => {
      const idx = this.activeAudioSources.indexOf(source);
      if (idx !== -1) {
        this.activeAudioSources.splice(idx, 1);
      }
      if (this.activeAudioSources.length === 0) {
        this.setAiSpeaking(false);
      }
    };
  }

  public stopAllPlayback(): void {
    for (const src of this.activeAudioSources) {
      try {
        src.stop();
        src.disconnect();
      } catch (ignored) {}
    }
    this.activeAudioSources = [];
    this.nextPlayTime = 0;
    this.setAiSpeaking(false);
  }

  public stop(): void {
    if (this.animFrameId !== null) {
      cancelAnimationFrame(this.animFrameId);
      this.animFrameId = null;
    }

    this.stopAllPlayback();

    if (this.processor) {
      this.processor.disconnect();
      this.processor = null;
    }
    if (this.micSource) {
      this.micSource.disconnect();
      this.micSource = null;
    }
    if (this.analyser) {
      this.analyser.disconnect();
      this.analyser = null;
    }
    if (this.micStream) {
      this.micStream.getTracks().forEach(track => track.stop());
      this.micStream = null;
    }
    if (this.audioCtx && this.audioCtx.state !== 'closed') {
      this.audioCtx.close();
      this.audioCtx = null;
    }

    this.isMuted = false;
    this.setAiSpeaking(false);
    this.onVolumeChange(0);
  }
}
