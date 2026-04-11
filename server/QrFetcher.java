import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import javax.imageio.ImageIO;
import java.awt.*;

/**
 * QrFetcher — fetches a QR code image from the QR Server API.
 * Works on any machine with internet access.
 * Falls back to a text placeholder if offline.
 */
public class QrFetcher {

    /**
     * Fetch a QR code image for the given URL.
     * @param url   the URL to encode
     * @param size  pixel size of the returned image
     * @return      BufferedImage of the QR code
     */
    public static BufferedImage fetch(String url, int size) {
        try {
            String encoded = URLEncoder.encode(url, "UTF-8");
            String apiUrl = "https://api.qrserver.com/v1/create-qr-code/?size="
                    + size + "x" + size + "&data=" + encoded + "&margin=10&format=png";

            HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream()) {
                    return ImageIO.read(is);
                }
            }
        } catch (Exception ignored) {}

        // Fallback: plain placeholder
        return makePlaceholder(url, size);
    }

    private static BufferedImage makePlaceholder(String url, int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(30, 31, 42));
        g.fillRect(0, 0, size, size);
        g.setColor(new Color(120, 122, 145));
        g.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        g.drawString("No internet —", 30, size/2 - 8);
        g.drawString("open URL manually:", 18, size/2 + 8);
        g.setFont(new Font("Consolas", Font.PLAIN, 9));
        // Wrap URL
        String[] parts = url.split("(?<=\\d\\d\\d\\d)");
        int y = size/2 + 28;
        for (String part : parts) { g.drawString(part, 10, y); y += 14; }
        g.dispose();
        return img;
    }
}