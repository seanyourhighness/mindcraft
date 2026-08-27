package net.clankerjockey.mod;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client-side microphone capture with energy-based voice-activity endpointing.
 *
 * <p>Continuously reads the default microphone as 16 kHz mono 16-bit PCM and
 * detects speech boundaries by RMS energy: a clip starts when energy crosses
 * {@code threshold} for {@code speechMs} and ends when it stays below for
 * {@code silenceMs}. Each detected clip is emitted to the listener as a
 * self-contained 16 kHz mono WAV (the exact format whisper-server expects).
 *
 * <p>Runs on a daemon thread; safe to start/stop from the client thread. A
 * missing or unavailable microphone degrades to no-op (listener never fires),
 * matching the mod's degraded-safe philosophy.
 */
public final class VoiceCapture {

    /** Receives detected speech clips as 16 kHz mono 16-bit WAV bytes. */
    public interface Listener {
        void onSpeech(byte[] wav);
    }

    public static final class Config {
        /** RMS energy (0..1 scale of 16-bit) that counts as speech. */
        public float threshold = 0.02f;
        /** Consecutive speech frames (ms) required to open a clip. */
        public int speechMs = 250;
        /** Consecutive silence frames (ms) required to close a clip. */
        public int silenceMs = 700;
        /** Max clip length (ms) before it is force-closed. */
        public int maxClipMs = 15000;
        /** Minimum clip length (ms) to emit (drop blips). */
        public int minClipMs = 300;
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNELS = 1;
    private static final int BITS = 16;
    private static final int FRAME_MS = 30; // ~480 samples/frame at 16 kHz

    private final Config config;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread worker;

    public VoiceCapture() {
        this(new Config());
    }

    public VoiceCapture(Config config) {
        this.config = config == null ? new Config() : config;
    }

    /**
     * Begin capturing on a daemon thread. Idempotent. Returns false (and does
     * nothing) if no microphone is available — the caller can fall back to
     * text-only input.
     */
    public synchronized boolean start(Listener listener) {
        if (running.get()) {
            return true;
        }
        AudioFormat format = new AudioFormat(SAMPLE_RATE, BITS, CHANNELS, true, true);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            return false;
        }
        TargetDataLine line;
        try {
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(format);
            line.start();
        } catch (LineUnavailableException | RuntimeException e) {
            return false;
        }
        running.set(true);
        worker = new Thread(new CaptureLoop(line, listener), "clankerjockey-voice-capture");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    /** Stop capturing and release the audio line. Idempotent. */
    public synchronized void stop() {
        running.set(false);
        Thread w = worker;
        if (w != null) {
            try {
                w.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
    }

    public boolean isCapturing() {
        return running.get();
    }

    private final class CaptureLoop implements Runnable {
        private final TargetDataLine line;
        private final Listener listener;

        CaptureLoop(TargetDataLine line, Listener listener) {
            this.line = line;
            this.listener = listener;
        }

        @Override
        public void run() {
            int frameSamples = SAMPLE_RATE * FRAME_MS / 1000;
            byte[] frame = new byte[frameSamples * 2];
            // Ring buffer of captured samples for the current clip.
            Deque<short[]> clip = new ArrayDeque<>();
            int speechFrames = 0;
            int silenceFrames = 0;
            boolean inSpeech = false;
            int clipFrames = 0;

            try {
                while (running.get()) {
                    int read = line.read(frame, 0, frame.length);
                    if (read <= 0) {
                        continue;
                    }
                    short[] samples = new short[read / 2];
                    for (int i = 0; i < samples.length; i++) {
                        samples[i] = (short) ((frame[i * 2] & 0xff) | (frame[i * 2 + 1] << 8));
                    }
                    float rms = rms(samples);
                    boolean voiced = rms >= config.threshold;

                    if (voiced) {
                        speechFrames++;
                        silenceFrames = 0;
                    } else {
                        silenceFrames++;
                        speechFrames = 0;
                    }

                    int speechFramesMs = speechFrames * FRAME_MS;
                    int silenceFramesMs = silenceFrames * FRAME_MS;

                    if (!inSpeech && speechFramesMs >= config.speechMs) {
                        inSpeech = true;
                        clip.clear();
                        clipFrames = 0;
                    }

                    if (inSpeech) {
                        clip.addLast(samples);
                        clipFrames++;
                        int clipMs = clipFrames * FRAME_MS;
                        boolean endBySilence = silenceFramesMs >= config.silenceMs;
                        boolean endByMax = clipMs >= config.maxClipMs;
                        if (endBySilence || endByMax) {
                            emit(clip, listener);
                            inSpeech = false;
                            clip.clear();
                            clipFrames = 0;
                            speechFrames = 0;
                            silenceFrames = 0;
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // line closed or device removed — stop quietly
            } finally {
                try {
                    line.stop();
                    line.close();
                } catch (RuntimeException ignored) {
                    // already closed
                }
            }
        }

        private void emit(Deque<short[]> clip, Listener l) {
            int total = 0;
            for (short[] s : clip) {
                total += s.length;
            }
            int clipMs = total * 1000 / SAMPLE_RATE;
            if (clipMs < config.minClipMs) {
                return; // too short — drop
            }
            short[] all = new short[total];
            int off = 0;
            for (short[] s : clip) {
                System.arraycopy(s, 0, all, off, s.length);
                off += s.length;
            }
            l.onSpeech(toWav(all));
        }

        private static float rms(short[] s) {
            double acc = 0;
            for (short v : s) {
                double d = v / 32768.0;
                acc += d * d;
            }
            return (float) Math.sqrt(acc / s.length);
        }

        /** Encode 16 kHz mono 16-bit PCM as a RIFF/WAV byte array. */
        private static byte[] toWav(short[] pcm) {
            int n = pcm.length;
            ByteArrayOutputStream out = new ByteArrayOutputStream(44 + n * 2);
            // RIFF header
            writeStr(out, "RIFF");
            writeInt(out, 36 + n * 2);
            writeStr(out, "WAVE");
            // fmt chunk
            writeStr(out, "fmt ");
            writeInt(out, 16);
            writeShort(out, 1); // PCM
            writeShort(out, CHANNELS);
            writeInt(out, SAMPLE_RATE);
            writeInt(out, SAMPLE_RATE * 2); // byte rate
            writeShort(out, 2); // block align
            writeShort(out, BITS);
            // data chunk
            writeStr(out, "data");
            writeInt(out, n * 2);
            for (short v : pcm) {
                out.write(v & 0xff);
                out.write((v >> 8) & 0xff);
            }
            return out.toByteArray();
        }

        private static void writeStr(ByteArrayOutputStream o, String s) {
            for (char c : s.toCharArray()) {
                o.write(c);
            }
        }

        private static void writeInt(ByteArrayOutputStream o, int v) {
            o.write(v & 0xff);
            o.write((v >> 8) & 0xff);
            o.write((v >> 16) & 0xff);
            o.write((v >> 24) & 0xff);
        }

        private static void writeShort(ByteArrayOutputStream o, int v) {
            o.write(v & 0xff);
            o.write((v >> 8) & 0xff);
        }
    }
}
