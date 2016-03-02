package com.sample.mapbox;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.google.android.gms.maps.model.LatLng;
import com.letsplaymobile.satbeams.geometry.GeodeticArc;
import com.letsplaymobile.satbeams.geometry.GreatCircleArc;
import com.letsplaymobile.satbeams.geometry.RhumbArc;

import java.util.LinkedList;
import java.util.List;

/**
 * Created by Crysberry on 4/16/15.
 */
public class Route2
{
    public static final String TAG = Route2.class.getSimpleName();

    /** Route segment geometry type
     *
     */
    public enum GEOMETRY {
        GREAT_CIRCLE, RHUMB
    }

    public final static String STORAGE_NAME = "Routes2";
    public final static String STORAGE_NAME_TEMP = "Routes2Temp";

    private final static String STORAGE_KEY_ROUTE_NODES = "STORAGE_KEY_ROUTE_NODES";
    private final static String STORAGE_KEY_ROUTE_SEGMENTS = "STORAGE_KEY_ROUTE_SEGMENTS";

    private SharedPreferences store;

    /** Aux list to support route nodes list adapter. Have to keep it in sync with the segments list.
     * Actually may be derived from the segments list.
     *
     */
    private LinkedList<LatLng> nodes = new LinkedList<>();

    /** Route geometry data
     */
    private LinkedList<GeodeticArc> segments = new LinkedList<>();

    /**
     *
     * @param context
     */
    public Route2(Context context) {
        this.store = context.getSharedPreferences(STORAGE_NAME, Context.MODE_PRIVATE);
    }

    /**
     *
     * @param context
     * @param storageFileName
     */
    public Route2(Context context, String storageFileName) {
        this.store = context.getSharedPreferences(storageFileName, Context.MODE_PRIVATE);
    }

    /** Add node to the route and create default geometry for the segment. No segment created if the route is empty.
     * @param point Route node or end of the segment added.
     */
    public void add(LatLng point)
    {
        if (nodes.size() == 0) {
            nodes.add(point);
            return;
        }

        segments.add( new GreatCircleArc( nodes.getLast(), point));
        nodes.add(point);
    }

    /** Add node to the route and create geometry for the segment of the given type. No segment created if the route is empty.
     * @param point Route node or end of the segment added.
     * @param geometry Type of the segment geometry
     */
    public void add(LatLng point, GEOMETRY geometry)
    {
        if (nodes.size() == 0) {
            nodes.add(point);
            return;
        }

        switch (geometry)
        {
            default:
            case GREAT_CIRCLE:
                segments.add( new GreatCircleArc( nodes.getLast(), point));
                break;
            case RHUMB:
                segments.add( new RhumbArc(nodes.getLast(), point));
                break;
        }

        nodes.add(point);
    }

    /** Add node and inbound segment to the route. No segment created if the route is empty.
     * @param point Route node or end of the segment added.
     * @param segment Type of the segment geometry
     */
    public void add(LatLng point, GeodeticArc segment)
    {
        if (nodes.size() == 0) {
            nodes.add(point);
            return;
        }

        segments.add(segment);
        nodes.add(point);
    }

    public List<LatLng> getNodes() {
        return nodes;
    }

    public List<GeodeticArc> getSegments() {
        return segments;
    }

    /** Get number of route nodes.
     */
    public int getNodesCount() {
        return nodes.size();
    }

    /** Get number of route nodes.
     */
    public int getSegmentsCount() {
        return segments.size();
    }

    /** Get route length as sum of segment lengths in given units.
     */
    public double length(GeodeticArc.DISTANCE_UNIT unit)
    {
        double l = 0.0;
        for(GeodeticArc segment : segments) {
            l += segment.getLength(unit);
        }
        return l;
    }

    public void load()
    {
        segments.clear();
        nodes.clear();

        // segment class names
        String[] segmentClassNames = TextUtils.split(store.getString(STORAGE_KEY_ROUTE_SEGMENTS, ""), ",");

        // nodes & segments
        String[] pairs = TextUtils.split(store.getString(STORAGE_KEY_ROUTE_NODES, ""), ";");
        for (int i = 0, s = pairs.length; i < s; i++)
        {
            String[] latLngString = TextUtils.split(pairs[i], ",");
            LatLng p = new LatLng( Double.parseDouble(latLngString[0]), Double.parseDouble(latLngString[1]));

            if (i == 0) {
                nodes.add(p);
                continue;
            }

            if (segmentClassNames[i - 1].equals(GreatCircleArc.class.getSimpleName()))
                segments.add( new GreatCircleArc( nodes.getLast(), p));
            else
                segments.add( new RhumbArc( nodes.getLast(), p));

            nodes.add(p);
        }
    }

    public void save()
    {
        SharedPreferences.Editor ed = store.edit();

        // nodes
        StringBuilder builder = new StringBuilder();
        for(LatLng p : nodes) {
           builder.append(p.latitude).append(",").append(p.longitude).append(";");
        }

        if (builder.length() > 0)
            builder.deleteCharAt(builder.length() - 1);

        ed.putString(STORAGE_KEY_ROUTE_NODES, builder.toString());

        // segments
        StringBuilder builder2 = new StringBuilder();
        for (GeodeticArc s : segments) {
            builder2.append(s.getClass().getSimpleName()).append(",");
        }

        if (builder2.length() > 0)
            builder2.deleteCharAt(builder2.length() - 1);

        ed.putString(STORAGE_KEY_ROUTE_SEGMENTS, builder2.toString());

        // commit
        ed.commit();
    }
}
