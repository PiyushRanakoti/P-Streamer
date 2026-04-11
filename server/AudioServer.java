import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public class AudioServer {
    static final int HTTP_PORT = 8080;
    static final int WS_PORT = 8081;
    static final int UDP_PORT = 9876;
    static final Set<WsClient> clients = ConcurrentHashMap.newKeySet();

    // GUI refs
    static JLabel statusLabel, clientsLabel, ipLabel;
    static JTextArea logArea;
    static JButton startBtn, stopBtn;
    static JLabel qrLabel;
    static volatile boolean running = false;
    static ServerSocket httpSock, wsSock;
    static DatagramSocket udpSock;
    static Process senderProcess;

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeLater(AudioServer::buildGui);
    }

    // ─────────────────────────────────────────────────
    // GUI
    // ─────────────────────────────────────────────────
    static void buildGui() {
        // Dark colour palette
        Color BG = new Color(15, 15, 20);
        Color PANEL = new Color(25, 26, 35);
        Color ACCENT = new Color(99, 102, 241); // indigo
        Color ACCENT2 = new Color(139, 92, 246); // violet
        Color TEXT = new Color(230, 232, 245);
        Color MUTED = new Color(120, 122, 145);
        Color SUCCESS = new Color(52, 211, 153);
        Color DANGER = new Color(239, 68, 68);

        UIManager.put("ToolTip.background", PANEL);
        UIManager.put("ToolTip.foreground", TEXT);

        JFrame frame = new JFrame("AudioStream Server");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(780, 560);
        frame.setResizable(false);
        frame.setLocationRelativeTo(null);
        frame.getContentPane().setBackground(BG);
        frame.setLayout(new BorderLayout(0, 0));

        // ── Header ───────────────────────────────────
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(PANEL);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(50, 52, 70)),
                new EmptyBorder(18, 24, 18, 24)));

        JLabel title = new JLabel("🎵  AudioStream Server");
        title.setFont(new Font("Segoe UI", Font.BOLD, 20));
        title.setForeground(TEXT);

        statusLabel = new JLabel("● Stopped");
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        statusLabel.setForeground(MUTED);

        header.add(title, BorderLayout.WEST);
        header.add(statusLabel, BorderLayout.EAST);
        frame.add(header, BorderLayout.NORTH);

        // ── Left panel: QR + IP ──────────────────────
        JPanel left = new JPanel();
        left.setBackground(PANEL);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setBorder(new EmptyBorder(24, 24, 24, 20));
        left.setPreferredSize(new Dimension(240, 0));

        JLabel qrTitle = new JLabel("Scan to Connect");
        qrTitle.setFont(new Font("Segoe UI", Font.BOLD, 13));
        qrTitle.setForeground(TEXT);
        qrTitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        // QR placeholder
        qrLabel = new JLabel();
        qrLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        qrLabel.setPreferredSize(new Dimension(190, 190));
        qrLabel.setMaximumSize(new Dimension(190, 190));
        qrLabel.setBorder(BorderFactory.createLineBorder(new Color(50, 52, 70), 2, true));

        // Placeholder image
        BufferedImage placeholder = new BufferedImage(190, 190, BufferedImage.TYPE_INT_RGB);
        Graphics2D pg = placeholder.createGraphics();
        pg.setColor(new Color(30, 31, 42));
        pg.fillRect(0, 0, 190, 190);
        pg.setColor(MUTED);
        pg.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        pg.drawString("Start server", 60, 90);
        pg.drawString("to see QR", 65, 108);
        pg.dispose();
        qrLabel.setIcon(new ImageIcon(placeholder));

        ipLabel = new JLabel("─── not running ───");
        ipLabel.setFont(new Font("Consolas", Font.PLAIN, 12));
        ipLabel.setForeground(MUTED);
        ipLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        clientsLabel = new JLabel("Clients: 0");
        clientsLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        clientsLabel.setForeground(MUTED);
        clientsLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        left.add(qrTitle);
        left.add(Box.createVerticalStrut(12));
        left.add(qrLabel);
        left.add(Box.createVerticalStrut(12));
        left.add(ipLabel);
        left.add(Box.createVerticalStrut(8));
        left.add(clientsLabel);
        left.add(Box.createVerticalGlue());

        // Start / Stop buttons
        startBtn = makeBtn("▶  Start", ACCENT, Color.WHITE);
        stopBtn = makeBtn("■  Stop", new Color(45, 45, 58), DANGER);
        stopBtn.setEnabled(false);

        startBtn.addActionListener(e -> startServer(BG, PANEL, ACCENT, TEXT, MUTED, SUCCESS, DANGER));
        stopBtn.addActionListener(e -> stopServer(MUTED));

        startBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        stopBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        left.add(startBtn);
        left.add(Box.createVerticalStrut(8));
        left.add(stopBtn);

        // ── Right panel: log ─────────────────────────
        JPanel right = new JPanel(new BorderLayout());
        right.setBackground(BG);
        right.setBorder(new EmptyBorder(20, 4, 20, 20));

        JLabel logTitle = new JLabel("Server Log");
        logTitle.setFont(new Font("Segoe UI", Font.BOLD, 13));
        logTitle.setForeground(TEXT);
        logTitle.setBorder(new EmptyBorder(0, 0, 10, 0));

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setBackground(PANEL);
        logArea.setForeground(new Color(160, 200, 160));
        logArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        logArea.setLineWrap(true);
        logArea.setBorder(new EmptyBorder(10, 12, 10, 12));
        logArea.setText("AudioStream Server ready.\nClick 'Start' to begin.\n");

        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(50, 52, 70), 1, true));
        scroll.setBackground(PANEL);
        scroll.getVerticalScrollBar().setBackground(PANEL);

        right.add(logTitle, BorderLayout.NORTH);
        right.add(scroll, BorderLayout.CENTER);

        // ── Ports info bar ───────────────────────────
        JPanel portBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 8));
        portBar.setBackground(new Color(20, 20, 28));
        portBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(40, 42, 58)));
        portBar.add(portChip("HTTP", "8080", ACCENT));
        portBar.add(portChip("WebSocket", "8081", ACCENT2));
        portBar.add(portChip("UDP", "9876", new Color(245, 158, 11)));

        // ── Assemble ─────────────────────────────────
        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(BG);
        center.add(left, BorderLayout.WEST);
        center.add(right, BorderLayout.CENTER);

        frame.add(center, BorderLayout.CENTER);
        frame.add(portBar, BorderLayout.SOUTH);
        frame.setVisible(true);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (senderProcess != null && senderProcess.isAlive())
                senderProcess.destroyForcibly();
        }));
    }

    static JButton makeBtn(String text, Color bg, Color fg) {
        JButton b = new JButton(text);
        b.setBackground(bg);
        b.setForeground(fg);
        b.setFont(new Font("Segoe UI", Font.BOLD, 13));
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setMaximumSize(new Dimension(192, 40));
        b.setPreferredSize(new Dimension(192, 40));
        return b;
    }

    static JPanel portChip(String label, String port, Color col) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        p.setBackground(new Color(20, 20, 28));
        JLabel l = new JLabel(label + ":");
        l.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        l.setForeground(new Color(100, 102, 130));
        JLabel v = new JLabel(port);
        v.setFont(new Font("Consolas", Font.BOLD, 11));
        v.setForeground(col);
        p.add(l);
        p.add(v);
        return p;
    }

    // ─────────────────────────────────────────────────
    // Start / Stop
    // ─────────────────────────────────────────────────
    static void startServer(Color BG, Color PANEL, Color ACCENT, Color TEXT, Color MUTED,
            Color SUCCESS, Color DANGER) {
        running = true;
        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        statusLabel.setForeground(SUCCESS);
        statusLabel.setText("● Running");

        String ip = getLocalIp();
        String url = "http://" + ip + ":" + HTTP_PORT;
        ipLabel.setForeground(new Color(99, 102, 241));
        ipLabel.setText(url);
        clientsLabel.setForeground(new Color(52, 211, 153));

        // Generate QR asynchronously (network call)
        qrLabel.setIcon(null);
        qrLabel.setText("Loading QR...");
        String urlFinal = url;
        new Thread(() -> {
            BufferedImage qrImg = generateQr(urlFinal, 190);
            SwingUtilities.invokeLater(() -> {
                qrLabel.setText(null);
                qrLabel.setIcon(new ImageIcon(qrImg));
            });
        }, "qr-fetch").start();

        log("Server started.");
        log("URL: " + url);
        log("Share QR code or URL with phone.");

        // ── Launch AudioSender as a hidden subprocess (no console window) ──
        new Thread(() -> {
            try {
                Thread.sleep(500); // give servers a moment to bind
                String javaExe = ProcessHandle.current().info().command().orElse("java");
                // Determine sender classpath — look for sender/ folder relative to cwd
                String senderCp;
                java.io.File senderDir = new java.io.File("sender");
                if (senderDir.exists()) {
                    // Compile sender on-the-fly if needed
                    java.io.File senderClass = new java.io.File("sender/AudioSender.class");
                    if (!senderClass.exists()) {
                        new ProcessBuilder("javac", "sender/AudioSender.java")
                                .redirectErrorStream(true).start().waitFor();
                    }
                    senderCp = "sender";
                } else {
                    // Same directory (flat layout)
                    java.io.File localClass = new java.io.File("AudioSender.class");
                    if (!localClass.exists()) {
                        new ProcessBuilder("javac", "AudioSender.java")
                                .redirectErrorStream(true).start().waitFor();
                    }
                    senderCp = ".";
                }

                ProcessBuilder pb = new ProcessBuilder(javaExe, "-cp", senderCp, "AudioSender");
                pb.redirectErrorStream(true);
                // On Windows, keep hidden (no new console)
                // ProcessBuilder doesn't open a console window by default when launched from a
                // GUI JVM
                senderProcess = pb.start();
                log("AudioSender started (hidden, PID " + senderProcess.pid() + ").");

                // Pipe sender stdout to the GUI log
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(senderProcess.getInputStream()));
                String line2;
                while ((line2 = br.readLine()) != null) {
                    final String msg = line2;
                    SwingUtilities.invokeLater(() -> log("[Sender] " + msg));
                }
            } catch (Exception e) {
                log("Could not start AudioSender: " + e.getMessage());
            }
        }, "sender-launch").start();

        // HTTP thread
        new Thread(() -> {
            try {
                httpSock = new ServerSocket(HTTP_PORT);
                while (running) {
                    try {
                        Socket s = httpSock.accept();
                        new Thread(() -> handleHttp(s)).start();
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                log("HTTP error: " + e.getMessage());
            }
        }, "http").start();

        // WebSocket thread
        new Thread(() -> {
            try {
                wsSock = new ServerSocket(WS_PORT);
                while (running) {
                    try {
                        Socket s = wsSock.accept();
                        new Thread(() -> handleWs(s)).start();
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                log("WS error: " + e.getMessage());
            }
        }, "ws").start();

        // UDP + jitter buffer (drain ALL queued frames per tick for low latency)
        new Thread(() -> {
            try {
                udpSock = new DatagramSocket(UDP_PORT);
                udpSock.setReceiveBufferSize(8192); // small OS buffer to avoid stale frames
                BlockingQueue<byte[]> jitterBuf = new LinkedBlockingQueue<>(50);
                log("Waiting for audio on UDP:" + UDP_PORT + "...");

                new Thread(() -> {
                    try {
                        byte[] b = new byte[8192];
                        while (running) {
                            DatagramPacket pkt = new DatagramPacket(b, b.length);
                            udpSock.receive(pkt);
                            byte[] frame = Arrays.copyOf(pkt.getData(), pkt.getLength());
                            if (!jitterBuf.offer(frame)) {
                                jitterBuf.poll(); // drop oldest if full
                                jitterBuf.offer(frame);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }, "udp-rx").start();

                while (running) {
                    Thread.sleep(20); // match 20ms frame size
                    byte[] data;
                    while ((data = jitterBuf.poll()) != null) {
                        final byte[] frame = data;
                        for (WsClient c : clients)
                            c.send(frame);
                    }
                }
            } catch (Exception e) {
                log("UDP error: " + e.getMessage());
            }
        }, "udp").start();
    }

    static void stopServer(Color MUTED) {
        running = false;
        try {
            if (httpSock != null)
                httpSock.close();
        } catch (Exception ignored) {
        }
        try {
            if (wsSock != null)
                wsSock.close();
        } catch (Exception ignored) {
        }
        try {
            if (udpSock != null)
                udpSock.close();
        } catch (Exception ignored) {
        }
        if (senderProcess != null && senderProcess.isAlive()) {
            senderProcess.destroyForcibly();
            senderProcess = null;
        }
        clients.clear();
        startBtn.setEnabled(true);
        stopBtn.setEnabled(false);
        statusLabel.setText("● Stopped");
        statusLabel.setForeground(MUTED);
        ipLabel.setForeground(MUTED);
        ipLabel.setText("─── not running ───");
        clientsLabel.setForeground(MUTED);
        clientsLabel.setText("Clients: 0");

        BufferedImage placeholder = new BufferedImage(190, 190, BufferedImage.TYPE_INT_RGB);
        Graphics2D pg = placeholder.createGraphics();
        pg.setColor(new Color(30, 31, 42));
        pg.fillRect(0, 0, 190, 190);
        pg.setColor(MUTED);
        pg.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        pg.drawString("Start server", 60, 90);
        pg.drawString("to see QR", 65, 108);
        pg.dispose();
        qrLabel.setIcon(new ImageIcon(placeholder));
        log("Server stopped.");
    }

    static void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + new java.text.SimpleDateFormat("HH:mm:ss").format(new Date()) + "] " + msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    static void updateClients() {
        SwingUtilities.invokeLater(() -> clientsLabel.setText("Clients: " + clients.size()));
    }

    // ─────────────────────────────────────────────────
    // QR Code — fetched from QR Server API
    // ─────────────────────────────────────────────────
    static BufferedImage generateQr(String url, int size) {
        return QrFetcher.fetch(url, size);
    }

    // ─────────────────────────────────────────────────
    // HTTP handler
    // ─────────────────────────────────────────────────
    static void handleHttp(Socket s) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String requestLine = in.readLine();
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
            }

            // Read the client HTML from the client/ folder next to the jar,
            // or serve the embedded fallback.
            String html;
            File clientFile = new File("../client/index.html");
            if (clientFile.exists()) {
                html = new String(java.nio.file.Files.readAllBytes(clientFile.toPath()), "UTF-8");
            } else {
                html = getEmbeddedHtml();
            }

            byte[] body = html.getBytes("UTF-8");
            OutputStream out = s.getOutputStream();
            PrintWriter pw = new PrintWriter(out);
            pw.print("HTTP/1.1 200 OK\r\n");
            pw.print("Content-Type: text/html; charset=utf-8\r\n");
            pw.print("Content-Length: " + body.length + "\r\n");
            pw.print("Connection: close\r\n");
            pw.print("\r\n");
            pw.flush();
            out.write(body);
            out.flush();
            s.close();
        } catch (Exception ignored) {
        }
    }

    // ─────────────────────────────────────────────────
    // WebSocket handler
    // ─────────────────────────────────────────────────
    static void handleWs(Socket s) {
        try {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String key = null;
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.startsWith("Sec-WebSocket-Key:"))
                    key = line.substring(18).trim();
            }
            if (key == null) {
                s.close();
                return;
            }

            String accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1")
                            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("UTF-8")));
            String resp = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
            out.write(resp.getBytes("UTF-8"));
            out.flush();

            WsClient client = new WsClient(s, out);
            clients.add(client);
            log("Phone connected! Total: " + clients.size());
            updateClients();

            client.readLoop(in);
            clients.remove(client);
            log("Phone disconnected. Total: " + clients.size());
            updateClients();
            s.close();
        } catch (Exception ignored) {
        }
    }

    // ─────────────────────────────────────────────────
    // WsClient
    // ─────────────────────────────────────────────────
    static class WsClient {
        final Socket socket;
        final OutputStream out;
        volatile boolean open = true;

        WsClient(Socket s, OutputStream o) {
            socket = s;
            out = o;
        }

        synchronized void send(byte[] data) {
            if (!open)
                return;
            try {
                int len = data.length;
                ByteBuffer frame;
                if (len <= 125) {
                    frame = ByteBuffer.allocate(2 + len);
                    frame.put((byte) 0x82);
                    frame.put((byte) len);
                } else if (len <= 65535) {
                    frame = ByteBuffer.allocate(4 + len);
                    frame.put((byte) 0x82);
                    frame.put((byte) 126);
                    frame.putShort((short) len);
                } else {
                    frame = ByteBuffer.allocate(10 + len);
                    frame.put((byte) 0x82);
                    frame.put((byte) 127);
                    frame.putLong(len);
                }
                frame.put(data);
                out.write(frame.array());
                out.flush();
            } catch (IOException e) {
                open = false;
            }
        }

        void readLoop(InputStream in) {
            try {
                byte[] header = new byte[2];
                while (open) {
                    if (in.read(header) < 2)
                        break;
                    int opcode = header[0] & 0x0F;
                    if (opcode == 0x8)
                        break;
                    long payloadLen = header[1] & 0x7F;
                    if (payloadLen == 126) {
                        byte[] ext = new byte[2];
                        in.read(ext);
                        payloadLen = ((ext[0] & 0xFF) << 8) | (ext[1] & 0xFF);
                    } else if (payloadLen == 127) {
                        byte[] ext = new byte[8];
                        in.read(ext);
                        payloadLen = ByteBuffer.wrap(ext).getLong();
                    }
                    boolean masked = (header[1] & 0x80) != 0;
                    if (masked)
                        in.readNBytes(4);
                    if (payloadLen > 0)
                        in.readNBytes((int) payloadLen);
                }
            } catch (IOException ignored) {
            }
            open = false;
        }
    }

    // ─────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────
    static String getLocalIp() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (iface.isLoopback() || !iface.isUp())
                    continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    // ─────────────────────────────────────────────────
    // Embedded client HTML (fallback if client/index.html missing)
    // ─────────────────────────────────────────────────
    static String getEmbeddedHtml() {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Live Audio</title>
                <style>
                  * { box-sizing: border-box; margin: 0; padding: 0; }
                  body { font-family: sans-serif; background: #0f0f0f; color: #fff;
                         display: flex; flex-direction: column; align-items: center;
                         justify-content: center; min-height: 100vh; gap: 24px; padding: 20px; }
                  h2 { font-size: 22px; font-weight: 500; }
                  button { padding: 16px 48px; font-size: 18px; border-radius: 50px;
                           background: #6366f1; color: #fff; border: none; cursor: pointer; }
                  #status { color: #888; font-size: 14px; }
                  #vu { width: 260px; height: 10px; background: #222; border-radius: 5px; overflow: hidden; }
                  #fill { height: 100%; width: 0%; background: #6366f1; border-radius: 5px; transition: width 0.04s; }
                </style>
                </head>
                <body>
                  <h2>🎵 Live Audio Stream</h2>
                  <button id="btn" onclick="start()">Tap to Listen</button>
                  <p id="status">Not connected</p>
                  <div id="vu"><div id="fill"></div></div>
                <script>
                function start() {
                  document.getElementById('btn').disabled = true;
                  document.getElementById('status').textContent = 'Connecting...';
                  const audioCtx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 44100, latencyHint: 'interactive' });
                  const ws = new WebSocket('ws://' + location.hostname + ':8081/ws');
                  ws.binaryType = 'arraybuffer';
                  let nextTime = audioCtx.currentTime;
                  ws.onopen  = () => document.getElementById('status').textContent = 'Live 🔴';
                  ws.onmessage = (e) => {
                    const pcm16 = new Int16Array(e.data);
                    const buffer = audioCtx.createBuffer(1, pcm16.length, 44100);
                    const ch = buffer.getChannelData(0);
                    for (let i = 0; i < pcm16.length; i++) ch[i] = pcm16[i] / 32768.0;
                    const src = audioCtx.createBufferSource();
                    src.buffer = buffer;
                    const analyser = audioCtx.createAnalyser(); analyser.fftSize = 64;
                    src.connect(analyser); analyser.connect(audioCtx.destination);
                    const now = audioCtx.currentTime;
                    if (nextTime < now || nextTime > now + 0.3) nextTime = now + 0.02;
                    src.start(nextTime); nextTime += buffer.duration;
                    const data = new Uint8Array(analyser.frequencyBinCount);
                    analyser.getByteFrequencyData(data);
                    const avg = data.reduce((a,b) => a+b, 0) / data.length;
                    document.getElementById('fill').style.width = Math.min(avg * 2.5, 100) + '%';
                  };
                  ws.onclose = () => { document.getElementById('status').textContent = 'Disconnected'; document.getElementById('btn').disabled = false; };
                  ws.onerror = () => { document.getElementById('status').textContent = 'Error — same WiFi?'; document.getElementById('btn').disabled = false; };
                }
                </script>
                </body>
                </html>
                """;
    }
}