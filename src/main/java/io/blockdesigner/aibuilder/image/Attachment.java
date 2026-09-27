package io.blockdesigner.aibuilder.image;

import io.blockdesigner.aibuilder.llm.ChatMessage;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * A reference picture attached to a message: scaled so its longer side is at most {@value #MAX_SIDE} pixels (enough for
 * a vision model, and small to send), encoded as JPEG, or PNG when it has transparency.
 */
public record Attachment(String name, int width, int height, ChatMessage.Image image, BufferedImage pixels) {
    public static final int MAX_SIDE = 1024;
    public static final List<String> EXTENSIONS = List.of("png", "jpg", "jpeg", "gif", "bmp");

    public static Attachment load(Path file) throws IOException {
        if (Files.size(file) > 40L * 1024 * 1024) throw new IOException("That picture is too big (over 40 MB)");
        BufferedImage img = ImageIO.read(file.toFile());
        if (img == null) throw new IOException("Not a picture BlockDesigner can read (use PNG, JPEG, GIF or BMP)");
        return of(file.getFileName().toString(), img);
    }

    public static Attachment of(String name, BufferedImage source) throws IOException {
        BufferedImage img = scale(source, MAX_SIDE);
        boolean alpha = hasTransparency(img);
        byte[] bytes = alpha ? png(img) : jpeg(img);
        String b64 = Base64.getEncoder().encodeToString(bytes);
        return new Attachment(name, img.getWidth(), img.getHeight(), new ChatMessage.Image(alpha ? "image/png" : "image/jpeg", b64), img);
    }

    static BufferedImage scale(BufferedImage src, int maxSide) {
        int w = src.getWidth(), h = src.getHeight();
        double f = Math.min(1, maxSide / (double) Math.max(w, h));
        int nw = Math.max(1, (int) Math.round(w * f)), nh = Math.max(1, (int) Math.round(h * f));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    static boolean hasTransparency(BufferedImage img) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) if ((img.getRGB(x, y) >>> 24) < 250) return true;
        }
        return false;
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static byte[] jpeg(BufferedImage img) throws IOException {
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(0.88f);
            writer.write(null, new IIOImage(rgb, null, null), p);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    public String describe() {
        return String.format(Locale.ROOT, "%s (%d×%d)", name, width, height);
    }
}
