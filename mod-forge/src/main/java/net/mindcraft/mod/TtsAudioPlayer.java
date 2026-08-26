package net.mindcraft.mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.util.valueproviders.SampledFloat;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plays raw TTS audio (IEEE-float 24 kHz mono WAV, as returned by
 * {@code TtsEngine.speakWav}) through Minecraft's sound system without any
 * resource registration.
 *
 * <p>Works by handing the {@code SoundManager} a {@link SoundInstance} whose
 * {@code getStream} is overridden to serve the in-memory bytes directly, so no
 * {@code .ogg}/{@code .json} sound file is ever loaded. Playback is submitted
 * on the client thread (Minecraft's sound system is not thread-safe).
 */
public final class TtsAudioPlayer {

    /** Pocket TTS emits 24 kHz mono IEEE-float PCM. */
    private static final int SAMPLE_RATE = 24000;
    private static final int CHANNELS = 1;
    private static final int BITS_PER_SAMPLE = 32;
    private static final boolean SIGNED = false;
    private static final boolean BIG_ENDIAN = false;

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private TtsAudioPlayer() {
    }

    /**
     * Play a TTS WAV on the client thread. No-op if not on the client or the
     * bytes are not a valid RIFF/WAVE header.
     *
     * @param wavBytes  RIFF/WAVE (IEEE-float 24 kHz mono) from {@code TtsEngine}
     * @param volume    0..1+ playback volume
     * @param pitch     1.0 = normal
     * @return true if a sound instance was queued
     */
    public static boolean play(byte[] wavBytes, float volume, float pitch) {
        if (wavBytes == null || wavBytes.length < 44
                || wavBytes[0] != 'R' || wavBytes[1] != 'I'
                || wavBytes[2] != 'F' || wavBytes[3] != 'F') {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getSoundManager() == null) {
            return false;
        }
        mc.submit(() -> {
            ResourceLocation loc = ResourceLocation.parse(
                    "mindcraft:tts/" + COUNTER.incrementAndGet());
            Sound sound = new Sound(loc.toString(),
                    ConstantFloat.of(1.0f), ConstantFloat.of(1.0f),
                    1, Sound.Type.FILE, false, false, 16);
            SoundInstance instance = new InMemorySoundInstance(loc, sound,
                    wavBytes, volume, pitch);
            mc.getSoundManager().play(instance);
        });
        return true;
    }

    /** A {@link SoundInstance} that streams from in-memory WAV bytes. */
    private static final class InMemorySoundInstance implements SoundInstance {
        private final ResourceLocation location;
        private final Sound sound;
        private final byte[] wav;
        private final float volume;
        private final float pitch;

        InMemorySoundInstance(ResourceLocation location, Sound sound,
                              byte[] wav, float volume, float pitch) {
            this.location = location;
            this.sound = sound;
            this.wav = wav;
            this.volume = volume;
            this.pitch = pitch;
        }

        @Override
        public ResourceLocation getLocation() {
            return location;
        }

        @Override
        public Sound getSound() {
            return sound;
        }

        @Override
        public net.minecraft.client.sounds.WeighedSoundEvents resolve(
                net.minecraft.client.sounds.SoundManager manager) {
            return new net.minecraft.client.sounds.WeighedSoundEvents(location, "");
        }

        @Override
        public SoundSource getSource() {
            return SoundSource.VOICE;
        }

        @Override
        public boolean isLooping() {
            return false;
        }

        @Override
        public boolean isRelative() {
            return false;
        }

        @Override
        public int getDelay() {
            return 0;
        }

        @Override
        public float getVolume() {
            return volume;
        }

        @Override
        public float getPitch() {
            return pitch;
        }

        @Override
        public double getX() {
            return 0;
        }

        @Override
        public double getY() {
            return 0;
        }

        @Override
        public double getZ() {
            return 0;
        }

        @Override
        public Attenuation getAttenuation() {
            return Attenuation.NONE;
        }

        @Override
        public CompletableFuture<AudioStream> getStream(SoundBufferLibrary library,
                                                         Sound sound, boolean preload) {
            return CompletableFuture.completedFuture(new InMemoryAudioStream(wav));
        }
    }

    /** An {@link AudioStream} backed by an in-memory WAV byte array. */
    private static final class InMemoryAudioStream implements AudioStream {
        private final byte[] data;
        private final AudioFormat format;
        private int offset;

        InMemoryAudioStream(byte[] data) {
            this.data = data;
            this.format = new AudioFormat(SAMPLE_RATE, BITS_PER_SAMPLE, CHANNELS,
                    SIGNED, BIG_ENDIAN);
        }

        @Override
        public AudioFormat getFormat() {
            return format;
        }

        @Override
        public ByteBuffer read(int length) {
            int remaining = data.length - offset;
            int n = Math.min(length, Math.max(remaining, 0));
            if (n <= 0) {
                return ByteBuffer.allocate(0);
            }
            ByteBuffer buf = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
            buf.put(data, offset, n);
            buf.flip();
            offset += n;
            return buf;
        }

        @Override
        public void close() {
            // nothing to release; the byte array is GC-eligible
        }
    }
}
