import javax.sound.sampled.*;
import java.net.*;

/**
 * AudioSender — captures system audio (or mic) and sends raw PCM16 frames
 * to AudioServer via UDP on localhost:9876.
 *
 * Launched automatically by AudioServer GUI — no separate window needed.
 *
 * CHANGES:
 * - 20ms frames (was 40ms) → lower latency
 * - Auto-gain normalization → audio is independent of PC volume
 * - Device selection logic preserved
 */
public class AudioSender {
    static final int SAMPLE_RATE = 44100;
    static final int FRAME_MS = 18; // ← 20ms for lower latency
    static final String SERVER = "127.0.0.1";
    static final int UDP_PORT = 9876;

    // Auto-gain: target RMS level (0.0–1.0 float scale)
    static final float TARGET_RMS = 0.25f;
    static final float MAX_GAIN = 40.0f; // cap so silence doesn't blow up
    static final float GAIN_ATTACK = 0.05f; // how fast gain rises
    static final float GAIN_RELEASE = 0.005f; // how fast gain falls

    static volatile float gain = 1.0f;

    public static void main(String[] args) throws Exception {
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        System.out.println("Available audio devices:");
        for (int i = 0; i < mixers.length; i++)
            System.out.println("  [" + i + "] " + mixers[i].getName());

        AudioFormat fmt = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        TargetDataLine line = null;

        // Pick device by index from args
        if (args.length > 0) {
            try {
                int idx = Integer.parseInt(args[0]);
                Mixer mixer = AudioSystem.getMixer(mixers[idx]);
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
                if (mixer.isLineSupported(info)) {
                    line = (TargetDataLine) mixer.getLine(info);
                    System.out.println("Using device [" + idx + "]: " + mixers[idx].getName());
                }
            } catch (Exception e) {
                System.out.println("Invalid device index: " + args[0]);
            }
        }

        // Auto-detect loopback / stereo mix
        if (line == null) {
            for (Mixer.Info mi : mixers) {
                String name = mi.getName().toLowerCase();
                if (name.contains("stereo mix") || name.contains("what u hear")
                        || name.contains("loopback") || name.contains("wave out")) {
                    try {
                        Mixer mixer = AudioSystem.getMixer(mi);
                        DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
                        if (mixer.isLineSupported(info)) {
                            line = (TargetDataLine) mixer.getLine(info);
                            System.out.println("Auto-detected loopback: " + mi.getName());
                            break;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        // Fallback to default mic
        if (line == null) {
            System.out.println("No loopback device found — using default microphone.");
            System.out.println("Tip: Enable 'Stereo Mix' in Windows Sound settings to capture PC audio.");
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
            line = (TargetDataLine) AudioSystem.getLine(info);
        }

        line.open(fmt);
        line.start();

        DatagramSocket sock = new DatagramSocket();
        InetAddress addr = InetAddress.getByName(SERVER);

        // 20ms frame
        int frameBytes = SAMPLE_RATE * FRAME_MS / 1000 * 2; // 16-bit = 2 bytes/sample
        byte[] raw = new byte[frameBytes];

        System.out.println(
                "Streaming " + (frameBytes / 2) + " samples/frame @ " + SAMPLE_RATE + "Hz  [20ms frames, auto-gain]");
        System.out.println("Sending to " + SERVER + ":" + UDP_PORT);
        System.out.println("Press Ctrl+C to stop.\n");

        while (true) {
            // Read exactly one full frame
            int got = 0;
            while (got < frameBytes)
                got += line.read(raw, got, frameBytes - got);

            // ── Auto-gain normalization ──────────────────────
            // Convert to short array, measure RMS, apply adaptive gain
            int samples = frameBytes / 2;
            short[] pcm = new short[samples];
            double sumSq = 0;
            for (int i = 0; i < samples; i++) {
                pcm[i] = (short) ((raw[i * 2] & 0xFF) | (raw[i * 2 + 1] << 8));
                double norm = pcm[i] / 32768.0;
                sumSq += norm * norm;
            }
            float rms = (float) Math.sqrt(sumSq / samples);

            // Adjust gain toward TARGET_RMS
            if (rms > 1e-5f) { // ignore silence
                float desiredGain = TARGET_RMS / rms;
                desiredGain = Math.min(desiredGain, MAX_GAIN);
                if (desiredGain < gain)
                    gain = gain + (desiredGain - gain) * GAIN_ATTACK; // fast attack
                else
                    gain = gain + (desiredGain - gain) * GAIN_RELEASE; // slow release
            }

            // Apply gain and re-encode to bytes
            byte[] out = new byte[frameBytes];
            for (int i = 0; i < samples; i++) {
                float s = pcm[i] * gain;
                // Soft clip to prevent hard clipping
                if (s > 32767f)
                    s = 32767f;
                if (s < -32768f)
                    s = -32768f;
                short boosted = (short) s;
                out[i * 2] = (byte) (boosted & 0xFF);
                out[i * 2 + 1] = (byte) ((boosted >> 8) & 0xFF);
            }

            DatagramPacket pkt = new DatagramPacket(out, frameBytes, addr, UDP_PORT);
            sock.send(pkt);
        }
    }
}