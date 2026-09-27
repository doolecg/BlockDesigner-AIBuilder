package io.blockdesigner.aibuilder.image;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImageHintsTest {
    /** A "house": brick-red walls on the lower two thirds, a dark roof above, on a transparent background. */
    private static BufferedImage house() {
        BufferedImage img = new BufferedImage(300, 300, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x97, 0x62, 0x53));
        g.fillRect(50, 120, 200, 180);
        g.setColor(new Color(0x43, 0x2B, 0x14));
        g.fillRect(40, 40, 220, 80);
        g.dispose();
        return img;
    }

    @Test
    void dominantColoursMapToTheNearestBlocks() {
        List<ImageHints.Hint> hints = ImageHints.dominant(house(), 5, ImageHints.BUILDING_BLOCKS.keySet(), ImageHints.BUILDING_BLOCKS::get);
        assertThat(hints).isNotEmpty();
        assertThat(hints.getFirst().block()).isEqualTo("bricks");
        assertThat(hints.getFirst().share()).isBetween(0.6, 0.75);   // 36000 of 53600 opaque pixels
        assertThat(hints).extracting(ImageHints.Hint::block).contains("dark_oak_planks");
        assertThat(hints.stream().mapToDouble(ImageHints.Hint::share).sum()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
        String text = ImageHints.describe(hints);
        assertThat(text).contains("-> bricks").contains("% of the picture");
    }

    @Test
    void oklabRoundTrips() {
        for (int rgb : new int[]{0x000000, 0xFFFFFF, 0x976253, 0x2D2F8F, 0x7CBD6B}) {
            assertThat(ImageHints.fromOklab(ImageHints.oklab(rgb))).isEqualTo(rgb);
        }
    }

    @Test
    void attachmentsAreScaledAndEncoded() throws IOException {
        BufferedImage big = new BufferedImage(3000, 1500, BufferedImage.TYPE_INT_RGB);
        Attachment a = Attachment.of("photo.jpg", big);
        assertThat(a.width()).isEqualTo(1024);
        assertThat(a.height()).isEqualTo(512);
        assertThat(a.image().mimeType()).isEqualTo("image/jpeg");
        byte[] bytes = Base64.getDecoder().decode(a.image().base64());
        assertThat(bytes[0] & 0xFF).isEqualTo(0xFF);   // JPEG starts FF D8
        Attachment p = Attachment.of("cutout.png", house());
        assertThat(p.image().mimeType()).isEqualTo("image/png");
        assertThat(p.describe()).isEqualTo("cutout.png (300×300)");
    }
}
