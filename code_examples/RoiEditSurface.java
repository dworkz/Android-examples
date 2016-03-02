package com.sample.mapbox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.support.v4.view.GestureDetectorCompat;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by Crysberry on 08.08.2015.
 */
public class RoiEditSurface extends View implements GestureDetector.OnGestureListener
{
    interface Listener {
        void onSingleTap(MotionEvent event);
    }

    private GestureDetectorCompat detector;


    private Listener listener;

    /**
     */
    private final Path tracePath = new Path();

    /**
     */
    private final Paint tracePaint = new Paint();

    /**
     */
    List<PointF> trace = new ArrayList<>();

    /**
     */
    public RoiEditSurface(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setupPaint();
        detector = new GestureDetectorCompat(context, this);
    }

    private void setupPaint() {
        tracePaint.setColor(Color.argb(128, 0, 255, 0));
        tracePaint.setAntiAlias(true);
        tracePaint.setStrokeWidth(5);
        tracePaint.setStyle(Paint.Style.STROKE);
        tracePaint.setStrokeJoin(Paint.Join.ROUND);
        tracePaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event)
    {
        if (detector.onTouchEvent(event)) {
            return true;
        }
        
        float eX = event.getX(), eY = event.getY();

        switch(event.getAction())
        {
            case MotionEvent.ACTION_DOWN:
                performClick();

                trace.clear();
                trace.add(new PointF(eX, eY));

                tracePath.rewind();
                tracePath.moveTo(eX, eY);
                break;

            case MotionEvent.ACTION_MOVE:
                trace.add(new PointF(eX, eY));
                tracePath.lineTo(event.getX(), event.getY());
                break;

            case MotionEvent.ACTION_UP:
                tracePath.close();
                break;
        }

        postInvalidate();
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawPath(tracePath, tracePaint);
    }

    public List<PointF> getTrace() {
        return trace;
    }

    public void reset() {
        tracePath.rewind();
        postInvalidate();
    }

    @Override
    public boolean onDown(MotionEvent e) {
        return false;
    }

    @Override
    public void onShowPress(MotionEvent e) {

    }

    @Override
    public boolean onSingleTapUp(MotionEvent e) {
        if (listener != null) {
            listener.onSingleTap(e);
        }
        return true;
    }

    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        return false;
    }

    @Override
    public void onLongPress(MotionEvent e) {

    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        return false;
    }
}
