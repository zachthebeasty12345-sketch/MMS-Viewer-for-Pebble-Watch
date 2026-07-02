package com.boxofdanger.mmsviewer;

import android.graphics.Bitmap;
import android.util.Log;

/**
 * Scales and dithers a Bitmap for Pebble Emery's 200×228, 64-color display.
 *
 * Pipeline:
 *   1. Scale to fit within MAX_WIDTH × MAX_HEIGHT (aspect-preserving)
 *   2. Floyd-Steinberg dither to Pebble's 2-bit-per-channel palette
 *   3. Pack pixels into GColor byte format (0bCC_BB_GG_RR, CC=11)
 */
public class ImageProcessor {

    private static final String TAG = "ImageProcessor";

    public static final int MAX_WIDTH  = 200;
    public static final int MAX_HEIGHT = 228;

    // Pebble's 2-bit channel levels mapped to 8-bit: 0, 85, 170, 255
    private static final int[] PALETTE = { 0, 85, 170, 255 };

    /** Result of processing an image. */
    public static class Result {
        public final byte[] gcolorData;   // packed GColor pixels, row-major
        public final int    width;
        public final int    height;

        Result(byte[] data, int w, int h) {
            this.gcolorData = data;
            this.width  = w;
            this.height = h;
        }
    }

    /**
     * Process a source bitmap into Pebble-ready GColor data.
     * The source bitmap is NOT recycled — caller manages its lifecycle.
     */
    public static Result process(Bitmap source) {
        // ── 1. Scale ──────────────────────────────────────────────
        Bitmap scaled = scaleFit(source, MAX_WIDTH, MAX_HEIGHT);
        int w = scaled.getWidth();
        int h = scaled.getHeight();

        Log.i(TAG, "Scaled to " + w + "×" + h);

        // ── 2. Extract channels as floats for error diffusion ────
        int[] pixels = new int[w * h];
        scaled.getPixels(pixels, 0, w, 0, 0, w, h);
        if (scaled != source) scaled.recycle();

        float[] rCh = new float[w * h];
        float[] gCh = new float[w * h];
        float[] bCh = new float[w * h];

        for (int i = 0; i < pixels.length; i++) {
            int px = pixels[i];
            rCh[i] = (px >> 16) & 0xFF;
            gCh[i] = (px >>  8) & 0xFF;
            bCh[i] =  px        & 0xFF;
        }
        pixels = null; // free early

        // ── 3. Floyd-Steinberg dithering ─────────────────────────
        byte[] gcolor = new byte[w * h];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;

                // Clamp current values
                float oldR = clamp(rCh[idx], 0, 255);
                float oldG = clamp(gCh[idx], 0, 255);
                float oldB = clamp(bCh[idx], 0, 255);

                // Find nearest palette index for each channel
                int newRI = nearestIndex(oldR);
                int newGI = nearestIndex(oldG);
                int newBI = nearestIndex(oldB);

                // Quantization error
                float errR = oldR - PALETTE[newRI];
                float errG = oldG - PALETTE[newGI];
                float errB = oldB - PALETTE[newBI];

                // Distribute error to neighbors (Floyd-Steinberg kernel)
                //          curr   7/16 →
                //   3/16   5/16   1/16
                diffuse(rCh, gCh, bCh, w, h, x + 1, y,     errR, errG, errB, 7.0f / 16);
                diffuse(rCh, gCh, bCh, w, h, x - 1, y + 1, errR, errG, errB, 3.0f / 16);
                diffuse(rCh, gCh, bCh, w, h, x,     y + 1, errR, errG, errB, 5.0f / 16);
                diffuse(rCh, gCh, bCh, w, h, x + 1, y + 1, errR, errG, errB, 1.0f / 16);

                // Pack to GColor: 0b11_RR_GG_BB
                gcolor[idx] = (byte) (0xC0 | (newRI << 4) | (newGI << 2) | newBI);
            }
        }

        Log.i(TAG, "Dithered to " + gcolor.length + " bytes");
        return new Result(gcolor, w, h);
    }

    // ── Private helpers ──────────────────────────────────────────────

    /** Scale bitmap to fit within maxW × maxH, preserving aspect ratio. */
    private static Bitmap scaleFit(Bitmap src, int maxW, int maxH) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();

        if (srcW <= maxW && srcH <= maxH) {
            return src; // already fits
        }

        float scale = Math.min((float) maxW / srcW, (float) maxH / srcH);
        int dstW = Math.max(1, Math.round(srcW * scale));
        int dstH = Math.max(1, Math.round(srcH * scale));

        return Bitmap.createScaledBitmap(src, dstW, dstH, true); // bilinear filter
    }

    /** Find the nearest 2-bit palette index (0–3) for an 8-bit value. */
    private static int nearestIndex(float val) {
        // Palette:  0=0, 1=85, 2=170, 3=255
        // Thresholds: 42.5, 127.5, 212.5
        if (val <  42.5f) return 0;
        if (val < 127.5f) return 1;
        if (val < 212.5f) return 2;
        return 3;
    }

    /** Add weighted error to a neighbor pixel if in bounds. */
    private static void diffuse(float[] r, float[] g, float[] b,
                                int w, int h,
                                int nx, int ny,
                                float errR, float errG, float errB,
                                float weight) {
        if (nx < 0 || nx >= w || ny < 0 || ny >= h) return;
        int idx = ny * w + nx;
        r[idx] += errR * weight;
        g[idx] += errG * weight;
        b[idx] += errB * weight;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
