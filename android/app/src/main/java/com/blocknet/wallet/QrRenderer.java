package com.blocknet.wallet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.EnumMap;
import java.util.Map;

/** Branded native payment QR. The quiet zone and high error correction remain part of the bitmap. */
public final class QrRenderer {
    public static final int BACKGROUND=0xffaaff00;
    private QrRenderer() { }
    public static Bitmap render(Context context,String value,int size) throws WriterException {
        if(value==null||value.trim().isEmpty())throw new IllegalArgumentException("Address unavailable");
        if(size<256)throw new IllegalArgumentException("Payment QR must be at least 256 pixels");
        Map<EncodeHintType,Object> hints=new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION,ErrorCorrectionLevel.H);
        hints.put(EncodeHintType.MARGIN,4);
        hints.put(EncodeHintType.CHARACTER_SET,"UTF-8");
        BitMatrix matrix=new MultiFormatWriter().encode(value,BarcodeFormat.QR_CODE,size,size,hints);
        int width=matrix.getWidth(),height=matrix.getHeight();int[] pixels=new int[width*height];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)pixels[y*width+x]=matrix.get(x,y)?0xff000000:BACKGROUND;
        Bitmap encoded=Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888);
        Bitmap result=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(result);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setShader(new BitmapShader(encoded,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP));
        canvas.drawRoundRect(new RectF(0,0,width,height),width*.035f,height*.035f,paint);paint.setShader(null);encoded.recycle();
        // A 16% center patch removes about 2.6% of the bitmap area; QR error correction is H.
        float side=Math.min(width,height)*.16f,cx=width/2f,cy=height/2f;paint.setColor(BACKGROUND);
        canvas.drawRoundRect(new RectF(cx-side/2,cy-side/2,cx+side/2,cy+side/2),side*.2f,side*.2f,paint);
        Drawable logo=context.getDrawable(R.drawable.ic_launcher_foreground);
        if(logo!=null){logo=logo.mutate();float bound=side*1.53f;logo.setBounds(Math.round(cx-bound/2),Math.round(cy-bound/2),Math.round(cx+bound/2),Math.round(cy+bound/2));logo.draw(canvas);}
        return result;
    }
}
