package kz.poolaim;

import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;

import java.nio.ByteBuffer;

/** Захват экрана в половинном разрешении через MediaProjection. Кадр берётся только по запросу. */
final class ScreenGrabber {

    private final MediaProjection mp;
    private final Handler handler;
    private final int dpi;
    private VirtualDisplay vd;
    private ImageReader reader;

    private int capW, capH, realW, realH;
    private byte[] frame;
    private int stride;
    private boolean has;
    private long frames;

    ScreenGrabber(MediaProjection mp, Handler handler, int dpi) {
        this.mp = mp;
        this.handler = handler;
        this.dpi = dpi;
    }

    private static int even(int v) {
        return Math.max(2, v & ~1);
    }

    synchronized void start(int rw, int rh) {
        realW = rw;
        realH = rh;
        capW = even(rw / 2);
        capH = even(rh / 2);
        reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);
        vd = mp.createVirtualDisplay("poolaim", capW, capH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
    }

    /** При повороте экрана меняем размер виртуального дисплея. */
    synchronized void resize(int rw, int rh) {
        if (vd == null || (rw == realW && rh == realH)) return;
        realW = rw;
        realH = rh;
        capW = even(rw / 2);
        capH = even(rh / 2);
        ImageReader old = reader;
        reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);
        vd.resize(capW, capH, dpi);
        vd.setSurface(reader.getSurface());
        try {
            old.close();
        } catch (Exception ignored) {
        }
        has = false;
    }

    /** Забирает самый свежий кадр, если он есть. Возвращает true, если хоть какой-то кадр уже есть. */
    synchronized boolean grab() {
        if (reader == null) return false;
        Image img = null;
        try {
            img = reader.acquireLatestImage();
        } catch (Exception ignored) {
        }
        if (img != null) {
            try {
                Image.Plane pl = img.getPlanes()[0];
                ByteBuffer buf = pl.getBuffer();
                int rs = pl.getRowStride();
                int need = rs * capH;
                if (frame == null || frame.length < need) frame = new byte[need];
                buf.rewind();
                buf.get(frame, 0, Math.min(need, buf.remaining()));
                stride = rs;
                has = true;
                frames++;
            } catch (Exception ignored) {
            } finally {
                img.close();
            }
        }
        return has;
    }

    synchronized long frameCount() {
        return frames;
    }

    /** Цвет 0xRRGGBB в точке экрана (sx, sy) в пикселях экрана. */
    synchronized int pixelAtScreen(float sx, float sy) {
        if (!has) return 0;
        int x = (int) (sx * capW / realW), y = (int) (sy * capH / realH);
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x >= capW) x = capW - 1;
        if (y >= capH) y = capH - 1;
        int o = y * stride + x * 4;
        if (o + 2 >= frame.length) return 0;
        return ((frame[o] & 255) << 16) | ((frame[o + 1] & 255) << 8) | (frame[o + 2] & 255);
    }

    synchronized void release() {
        try {
            if (vd != null) vd.release();
        } catch (Exception ignored) {
        }
        try {
            if (reader != null) reader.close();
        } catch (Exception ignored) {
        }
        vd = null;
        reader = null;
        try {
            mp.stop();
        } catch (Exception ignored) {
        }
    }
}
