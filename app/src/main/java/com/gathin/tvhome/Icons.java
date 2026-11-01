package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Small line icons drawn in code on a 24 x 24 grid, so the screens built in code need no image files. */
public final class Icons {
    private Icons() {}

    public static final String DASH = "dash", HOME = "home", APPS = "apps", DEBLOAT = "debloat", MONITOR = "monitor",
            SETTINGS = "settings", PLUG = "plug", CHECK = "check", CHEVRON = "chevron", CHEVRON_DOWN = "chevron_down";

    public static Drawable of(String kind, int color) {
        return new Icon(kind, color);
    }

    private static final class Icon extends Drawable {
        private final String kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Icon(String kind, int color) {
            this.kind = kind;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float s = Math.min(b.width(), b.height()) / 24f;
            c.save();
            c.translate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f);
            c.scale(s, s);
            paint.setStrokeWidth(1.9f);
            paint.setStyle(Paint.Style.STROKE);
            switch (kind) {
                case DASH:
                    for (int i = 0; i < 4; i++) c.drawRoundRect(new RectF(4 + (i % 2) * 9, 4 + (i / 2) * 9, 11 + (i % 2) * 9, 11 + (i / 2) * 9), 1.6f, 1.6f, paint);
                    break;
                case HOME: {
                    Path p = new Path();
                    p.moveTo(3.5f, 11f);
                    p.lineTo(12f, 3.8f);
                    p.lineTo(20.5f, 11f);
                    p.moveTo(5.5f, 9.5f);
                    p.lineTo(5.5f, 20f);
                    p.lineTo(18.5f, 20f);
                    p.lineTo(18.5f, 9.5f);
                    c.drawPath(p, paint);
                    c.drawRect(10f, 14f, 14f, 20f, paint);
                    break;
                }
                case APPS:
                    for (int i = 0; i < 9; i++) c.drawCircle(6 + (i % 3) * 6, 6 + (i / 3) * 6, 1.5f, paint);
                    break;
                case DEBLOAT: {
                    Path p = new Path();
                    p.moveTo(5f, 7f);
                    p.lineTo(19f, 7f);
                    p.moveTo(9f, 7f);
                    p.lineTo(9f, 4.5f);
                    p.lineTo(15f, 4.5f);
                    p.lineTo(15f, 7f);
                    p.moveTo(6.5f, 7f);
                    p.lineTo(7.5f, 20f);
                    p.lineTo(16.5f, 20f);
                    p.lineTo(17.5f, 7f);
                    p.moveTo(10f, 11f);
                    p.lineTo(10f, 16f);
                    p.moveTo(14f, 11f);
                    p.lineTo(14f, 16f);
                    c.drawPath(p, paint);
                    break;
                }
                case MONITOR: {
                    Path p = new Path();
                    p.moveTo(3f, 13f);
                    p.lineTo(7f, 13f);
                    p.lineTo(9.5f, 6f);
                    p.lineTo(14f, 19f);
                    p.lineTo(16.5f, 13f);
                    p.lineTo(21f, 13f);
                    c.drawPath(p, paint);
                    break;
                }
                case SETTINGS: {
                    for (int i = 0; i < 3; i++) {
                        float y = 6 + i * 6, x = i == 1 ? 15f : 9f;
                        c.drawLine(4f, y, 20f, y, paint);
                        paint.setStyle(Paint.Style.FILL);
                        c.drawCircle(x, y, 2.4f, paint);
                        paint.setStyle(Paint.Style.STROKE);
                    }
                    break;
                }
                case PLUG: {
                    Path p = new Path();
                    p.moveTo(9f, 3.5f);
                    p.lineTo(9f, 8f);
                    p.moveTo(15f, 3.5f);
                    p.lineTo(15f, 8f);
                    p.moveTo(6.5f, 8f);
                    p.lineTo(17.5f, 8f);
                    p.lineTo(17.5f, 12f);
                    p.cubicTo(17.5f, 15f, 15f, 16.5f, 12f, 16.5f);
                    p.cubicTo(9f, 16.5f, 6.5f, 15f, 6.5f, 12f);
                    p.close();
                    p.moveTo(12f, 16.5f);
                    p.lineTo(12f, 20.5f);
                    c.drawPath(p, paint);
                    break;
                }
                case CHECK: {
                    Path p = new Path();
                    p.moveTo(5f, 12.5f);
                    p.lineTo(10f, 17.5f);
                    p.lineTo(19f, 7f);
                    paint.setStrokeWidth(2.6f);
                    c.drawPath(p, paint);
                    break;
                }
                case CHEVRON: {
                    Path p = new Path();
                    p.moveTo(9f, 5f);
                    p.lineTo(16f, 12f);
                    p.lineTo(9f, 19f);
                    c.drawPath(p, paint);
                    break;
                }
                case CHEVRON_DOWN: {
                    Path p = new Path();
                    p.moveTo(5f, 9f);
                    p.lineTo(12f, 16f);
                    p.lineTo(19f, 9f);
                    c.drawPath(p, paint);
                    break;
                }
                default:
                    break;
            }
            c.restore();
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
