package com.sample.mapbox;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;

import com.goebl.simplify.PointExtractor;
import com.goebl.simplify.Simplify;
import com.mapbox.mapboxsdk.events.MapListener;
import com.mapbox.mapboxsdk.events.RotateEvent;
import com.mapbox.mapboxsdk.events.ScrollEvent;
import com.mapbox.mapboxsdk.events.ZoomEvent;
import com.mapbox.mapboxsdk.overlay.Overlay;
import com.mapbox.mapboxsdk.views.MapView;
import com.mapbox.mapboxsdk.views.util.Projection;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.CoordinateFilter;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.MultiPolygon;
import com.vividsolutions.jts.geom.Polygon;

/**
 * Created by Crysberry on 8/10/15.
 */
public class RegionOfInterestOverlay extends Overlay implements MapListener
{
    private static final double SIMPLIFY_TOLERANCE = 1.0;

    private Geometry roiGeographic;

    private Geometry roiProjected;

    private final Path path = new Path();

    private final Paint paint = new Paint();

    /** Leftmost world, rightmost world, center world indices used to draw footprint
     * instances on "infinite" map scrolling stripe.
     * TODO Use structure instead of arrays to describe the visible "worlds"
     */
    int[] visibleWorldInstances = new int[3];

    private boolean isDirty = true;

    private boolean isHidden = false;

    /**
     *
     * @param roiGeographic
     */
    public RegionOfInterestOverlay(Geometry roiGeographic) {
        this.roiGeographic = roiGeographic;
    }

    /**
     */
    public void hide() {
        isHidden = true;
    }

    /**
     *
     */
    public void show() {
        isHidden = false;
    }

    private class ConverterToProjected implements CoordinateFilter
    {
        Projection projection;
        PointF reuse = new PointF();

        public ConverterToProjected(Projection projection) {
            this.projection = projection;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(0x80, 0xff, 0x88, 0x00));
        }

        @Override
        public void filter(Coordinate c) {
            projection.toMapPixelsProjected(c.y, c.x, reuse);
            c.x = reuse.x;
            c.y = reuse.y;
        }
    }

    public void prepare(Projection projection) {
        prepareProjected(projection);
        updateGraphics(projection);
    }

    public void update(Projection projection) {
        updateGraphics(projection);
    }

    public void rebuild(Projection projection) {
        prepareProjected(projection);
        updateGraphics(projection);
    }

    public void prepareProjected(Projection projection)
    {
        roiProjected = roiGeographic != null? (Geometry) roiGeographic.clone(): null;
        if (roiProjected != null)
            roiProjected.apply(new ConverterToProjected(projection));
    }

    public void reset() {
        path.rewind();
        roiGeographic = null;
    }

    public void reset(Geometry roiGeographic, Projection projection) {
        this.roiGeographic = roiGeographic;
        prepareProjected(projection);
        updateGraphics(projection);
    }

    private final PointF workPoint = new PointF();

    private Simplify<PointF> simplifier = new Simplify<>(new PointF[0], new PointExtractor<PointF>() {
        @Override
        public double getX(PointF point) {
            return point.x;
        }

        @Override
        public double getY(PointF point) {
            return point.y;
        }
    });

    private void updateGraphics(Projection projection)
    {
        path.rewind();

        if (roiProjected instanceof Polygon) {
            addPolygon((Polygon) roiProjected, projection);
        }
        else if (roiProjected instanceof MultiPolygon) {
            MultiPolygon multiPolygon = (MultiPolygon) roiProjected;
            for (int i = 0, n = multiPolygon.getNumGeometries(); i < n; i++) {
                addPolygon((Polygon) multiPolygon.getGeometryN(i), projection);
            }
        }

        isDirty = false;
    }

    private void addPolygon(Polygon polygon, Projection projection)
    {
        Coordinate[] projectedCoordinates = polygon.getExteriorRing().getCoordinates();

        PointF[] verticesToReduce = new PointF[projectedCoordinates.length];
        for (int i = 0; i < verticesToReduce.length; i++) {
            workPoint.set((float) projectedCoordinates[i].x,(float) projectedCoordinates[i].y);
            verticesToReduce[i] = projection.toMapPixelsTranslated(workPoint, null);
        }

        PointF[] vertices = simplifier.simplify(verticesToReduce, SIMPLIFY_TOLERANCE, true);

        path.moveTo(vertices[0].x, vertices[0].y);
        for (int i = 0; i < vertices.length; i++) {
            path.lineTo(vertices[i].x, vertices[i].y);
        }
    }

    @Override
    protected void draw(Canvas canvas, MapView mapView, boolean shadow) {
        if (shadow || null == roiProjected || isHidden)
            return;

        canvas.save();

        Projection projection = mapView.getProjection();
        int worldWidth = projection.getWorldWidth();

        if (isDirty)
            updateGraphics(projection);

        projection.getVisibleWorlds(visibleWorldInstances);

        if (visibleWorldInstances[0] == visibleWorldInstances[1]) {
            // single world instance visible
            drawInstance(canvas, worldWidth * visibleWorldInstances[0]);
        }
        else {
            // multiple world instances visible
            drawInstance(canvas, worldWidth * visibleWorldInstances[0]);
            for (int iWorld = visibleWorldInstances[0] + 1; iWorld <= visibleWorldInstances[1]; iWorld++) {
                drawInstance(canvas, worldWidth);
            }
        }

        canvas.restore();
    }

    private void drawInstance(Canvas canvas, float translation) {
        canvas.translate(translation, 0);
        canvas.drawPath(path, paint);
    }

    //
    // *** Map Listener Interface
    //

    @Override
    public void onScroll(ScrollEvent event) {

    }

    @Override
    public void onZoom(ZoomEvent event) {
        isDirty = true;
    }

    @Override
    public void onRotate(RotateEvent event) {

    }
}
