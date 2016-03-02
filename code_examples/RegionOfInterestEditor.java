package com.sample.mapbox;

import android.content.res.AssetManager;
import android.graphics.PointF;
import android.os.AsyncTask;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.mapbox.mapboxsdk.geometry.LatLng;
import com.mapbox.mapboxsdk.views.MapView;
import com.mapbox.mapboxsdk.views.util.Projection;
import com.vividsolutions.jts.algorithm.CGAlgorithms;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.Polygon;
import com.vividsolutions.jts.geom.MultiPolygon;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import butterknife.Bind;
import butterknife.ButterKnife;
import butterknife.OnClick;

/**
 * Created by Crysberry on 08.08.2015.
 */
public class RegionOfInterestEditor implements RoiEditSurface.Listener
{
    public static final String TAG = RegionOfInterestEditor.class.getSimpleName();

    private static final float MAX_TOUCH_SIZE_2 = 36;

    public interface Listener {
        void onRoiChanged(Geometry roi, Set<String> countries);
        void onRoiCleared();
        void RoiEditorClosed();
        void previewFilter();
        void applyFilter();
        void openCountriesSelector();
    }

    private class EditGestureDetector extends GestureDetector.SimpleOnGestureListener
    {
        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            return super.onSingleTapUp(e);
        }
    }

    private enum GeometryAction {
        INTERSECT, INCLUDE, CUTOFF, EXCLUDE
    }

    /** This is overlay with the buttons and drawing surface.
     */
    private final View editorRootView;

    /**
     */
    private final MapView mapView;

    /**
     */
    private Listener listener;

    /** Polygon or MultiPolygon which is result of union of countries selected
     * or manually outlined polygons.
     */
    private Geometry roiGeometry;

    /** Set of countries in case ROI is a union of countries polygons.
     *
     */
    private Set<String> roiCountries = new LinkedHashSet<>();


    /** ROI persistence object.
     */
    private RegionOfInterest roiPersistence, roiPersistenceTemporary;

    /**
     */
    BaseGeometryLoadTask baseGeometryLoadTask = new BaseGeometryLoadTask();

    /**
     */
    TraceEditTask traceEditTask;

    // ***

    @Bind(R.id.roiEditSurface)
    RoiEditSurface editSurface;

    @Bind(R.id.roiProgress)
    LinearLayout roiProgressWidget;

    @Bind(R.id.roiProgressMessage)
    TextView roiProgressMessage;

    /**
     *
     * @param editorView
     * @param mapView
     * @param listener
     */
    public RegionOfInterestEditor(View editorView, MapView mapView, Listener listener)
    {
        this.editorRootView = editorView;
        this.mapView = mapView;
        this.listener = listener;

        ButterKnife.bind(this, editorView);

        editSurface.setListener(this);
    }

    /**
     */
    public void open()
    {
        // load base geometry
        if (baseGeometryLoadTask.getStatus() == AsyncTask.Status.PENDING) {
            baseGeometryLoadTask.execute();
        }

        // --
        restore();

        // show editor
        editorRootView.setVisibility(View.VISIBLE);
    }


    public void setTo(RegionOfInterest src) {
        roiGeometry = src.getGeometry(mapView.getContext());
        roiCountries = new LinkedHashSet<>(src.getCountryNames(mapView.getContext()));
    }

    private void restore()
    {
        if (null == roiPersistence) {
            roiPersistence = new RegionOfInterest();
        }

        roiPersistence.restore(mapView.getContext());

        roiGeometry = roiPersistence.getGeometry(mapView.getContext());

        List<String> countryNamesList = roiPersistence.getCountryNames(mapView.getContext());
        if (countryNamesList != null)
            roiCountries = new LinkedHashSet<>(roiPersistence.getCountryNames(mapView.getContext()));
        else
            roiCountries = new LinkedHashSet<>();

        if (listener != null) {
            listener.onRoiChanged(roiGeometry, roiCountries);
        }
    }

    public void close() {
        editorRootView.setVisibility(View.GONE);
        editSurface.reset();
        if (listener != null)
            listener.RoiEditorClosed();
    }

    /** RoiEditSurface.Listener implementation
     *
     * @param event
     */
    @Override
    public void onSingleTap(MotionEvent event) {
        TapEditTask task = new TapEditTask(event);
        task.execute();
    }

    /** Add all countries intersected by the current sketch/outline to current region of interest selection.
     */
    @OnClick(R.id.roiIntersectCountries) public void intersectedCountries() {
        if (null == traceEditTask || traceEditTask.getStatus() == AsyncTask.Status.FINISHED) {
            traceEditTask = new TraceEditTask(GeometryAction.INTERSECT);
            traceEditTask.execute();
        }
    }

    /** Add all countries contained in the current sketch/outline to current region of interest selection.
     */
    @OnClick(R.id.roiIncludeCountries) public void includeCountries() {
        if (null == traceEditTask || traceEditTask.getStatus() == AsyncTask.Status.FINISHED) {
            traceEditTask = new TraceEditTask(GeometryAction.INCLUDE);
            traceEditTask.execute();
        }
    }

    /** Add the sketch/outline as is to current region of interest selection.
     */
    @OnClick(R.id.roiAddOutline) public void addOutline()
    {
        Projection projection = mapView.getProjection();

        // create polygon from the trace points sequence
        List<PointF> trace = editSurface.getTrace();
        int traceLength = trace.size();
        if (traceLength == 0)
            return;

        Coordinate[] geoCoordinates = new Coordinate[traceLength + 1];
        for (int i = 0; i < traceLength; i++) {
            PointF sp = trace.get(i);
            LatLng ll  = (LatLng) projection.fromPixels(sp.x, sp.y);
            geoCoordinates[i] = new Coordinate(ll.getLongitude(), ll.getLatitude());
        }

        geoCoordinates[traceLength] = geoCoordinates[0];

        // reverse winding order in case it was wrong
        if (! CGAlgorithms.isCCW(geoCoordinates))
        {
            for (int i = 0, j = geoCoordinates.length - 1; i < j; i++, j--) {
                Coordinate z = geoCoordinates[i];
                geoCoordinates[i] = geoCoordinates[j];
                geoCoordinates[j] = z;
            }
        }

        roiGeometry = CountryBoundaries.getInstance().polygonOf(geoCoordinates);
        roiCountries.clear();
        if (listener != null) {
            listener.onRoiChanged(roiGeometry, null);
        }
    }

    /** Countries Picker<br/><br/>
     *
     * Open popup providing list of countries with checkable items. Contains filters
     * All", "Selected" and supports quick scrolling with alphabetic search.
     */
    @OnClick(R.id.roiCountriesPicker) public void countriesPicker() {
        if (null == roiPersistenceTemporary) {
            roiPersistenceTemporary = new RegionOfInterest(true);
        }
        roiPersistenceTemporary.save(roiGeometry, new ArrayList<>(roiCountries), mapView.getContext());
        if (listener != null) {
            listener.openCountriesSelector();
        }
    }

    /** Remove countries intersected by current sketch (finger outline) from ROI collection.
     */
    @OnClick(R.id.roiCutOffCountries) public void cutOffCountries() {
        if (null == traceEditTask || traceEditTask.getStatus() == AsyncTask.Status.FINISHED) {
            traceEditTask = new TraceEditTask(GeometryAction.CUTOFF);
            traceEditTask.execute();
        }
    }

    /** Remove countries contained inside the current sketch (finger outline) from ROI collection.
     */
    @OnClick(R.id.roiExcludeCountries) public void excludeCountries() {
        if (null == traceEditTask || traceEditTask.getStatus() == AsyncTask.Status.FINISHED) {
            traceEditTask = new TraceEditTask(GeometryAction.EXCLUDE);
            traceEditTask.execute();
        }
    }

    /** Empty current ROI selection.
     */
    @OnClick(R.id.roiClearAll) public void clearAll() {
        roiGeometry = null;
        roiCountries.clear();
        if (listener != null) {
            listener.onRoiCleared();
        }
    }

    /** Restore current ROI from last saved version.
     */
    @OnClick(R.id.roiRestore) public void restoreRegionOfInterest() {
        restore();
    }

    /** Temporarily save ROI and display the filter preview.<br/><br/>
     *
     * Save current edition of ROI to the persistent storage as a temporary version for preview
     * and open preview popup. The popup has "Apply" ("Save") button to apply filter and close the
     * editor and "Close" button to close the popup and return to ROI editing.<br><br>
     *
     *  TODO Preview popup may contain option to bypass or account on the general filter.
     */
    @OnClick(R.id.roiPreview) public void previewFilter() {
        if (null == roiPersistenceTemporary) {
            roiPersistenceTemporary = new RegionOfInterest(true);
        }
        roiPersistenceTemporary.save(roiGeometry, new ArrayList<>(roiCountries), mapView.getContext());
        if (listener != null) {
            listener.previewFilter();
        }
    }

    /** Save ROI, do not apply it.<br/><br/>
     *
     * Save current edition of ROI to the persistent storage and re-apply
     * general filter if it's on.
     */
    @OnClick(R.id.roiSave) public void saveRegionOfInterest() {
        if (null == roiPersistence) {
            roiPersistence = new RegionOfInterest();
        }
        roiPersistence.save(roiGeometry, new ArrayList<>(roiCountries), mapView.getContext());
//        if (listener != null) {
//            listener.applyFilter();
//        }
        close();
    }

    /**
     */
    @OnClick(R.id.roiCloseEditor) public void closeEditor() {
        close();
    }

    /** Task to load base geometry, boundaries of countries.
     */
    private class BaseGeometryLoadTask extends AsyncTask<Void, Void, Void>
    {
        AssetManager assetManager;

        @Override
        protected void onPreExecute() {
            assetManager = editorRootView.getContext().getAssets();
            startProgress(R.string.roi_editor_progress_message_loading);
        }

        @Override
        protected Void doInBackground(Void... params) {
            try {
                CountryBoundaries countryBoundaries = CountryBoundaries.getInstance();
                if (! countryBoundaries.isLoaded()) {
                    countryBoundaries.loadKml(assetManager);
                }
            }
            catch (Exception e) {
                Log.d(TAG, "Cannot load countries geometry!", e);
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            stopProgress();
        }
    }

    private class TapEditTask extends AsyncTask<Void, Void, Boolean>
    {
        /** touch center (x, y) and radius "r"
         */
        private final float x, y, r;

        private Projection projection;
        private AssetManager assetManager;


        public TapEditTask(MotionEvent event) {
            this.x = event.getX();
            this.y = event.getY();
            this.r = event.getSize() * MAX_TOUCH_SIZE_2;
        }

        @Override
        protected void onPreExecute() {
            projection = mapView.getProjection();
            assetManager = editorRootView.getContext().getAssets();
            startProgress(R.string.roi_editor_progress_message_search);
        }

        @Override
        protected Boolean doInBackground(Void... params)
        {
            try
            {
                CountryBoundaries countryBoundaries = CountryBoundaries.getInstance();
                if (!countryBoundaries.isLoaded()) {
                    countryBoundaries.loadKml(assetManager);
                }

                LatLng[] lls = new LatLng[]{
                    (LatLng) projection.fromPixels(x - r, y + r),
                    (LatLng) projection.fromPixels(x - r, y - r),
                    (LatLng) projection.fromPixels(x + r, y - r),
                    (LatLng) projection.fromPixels(x + r, y + r),
                    (LatLng) projection.fromPixels(x - r, y + r),
                };

                Coordinate[] geoCoordinates = new Coordinate[5];
                for (int i = 0; i < 5; i++) {
                    geoCoordinates[i] = new Coordinate(lls[i].getLongitude(), lls[i].getLatitude());
                }

                List<String> results = countryBoundaries.intersect(geoCoordinates);
                for (String result : results) {
                    if (roiCountries.contains(result)) {
                        roiCountries.remove(result);
                    }
                    else {
                        roiCountries.add(result);
                    }
                }

                if (roiCountries.size() > 0) {
                    roiGeometry = countryBoundaries.getCountriesUnionRegion(roiCountries);
                }
                else {
                    if (listener != null) {
                        listener.onRoiCleared();
                    }
                }
            }
            catch (Exception e) {
                Log.d(TAG, "Failed to find country contained tap!", e);
                return false;
            }

            return true;
        }

        @Override
        protected void onPostExecute(Boolean isSuccess) {
            stopProgress();
            if (listener != null && isSuccess) {
                listener.onRoiChanged(roiGeometry, roiCountries);
            }
        }
    }


    /**
     */
    private class TraceEditTask extends AsyncTask<Void, Void, Boolean>
    {
        private final GeometryAction action;

        private List<PointF> fingerTraceCoordinates;
        private int traceLength;
        private Projection projection;
        private AssetManager assetManager;

        public TraceEditTask(GeometryAction action) {
            this.action = action;
        }

        @Override
        protected void onPreExecute() {
            fingerTraceCoordinates = editSurface.getTrace();
            traceLength = fingerTraceCoordinates.size();
            projection = mapView.getProjection();
            assetManager = editorRootView.getContext().getAssets();
            startProgress(R.string.roi_editor_progress_message_search);
        }

        @Override
        protected Boolean doInBackground(Void... params)
        {
            try
            {
                if (traceLength == 0)
                    return false;

                CountryBoundaries countryBoundaries = CountryBoundaries.getInstance();
                if (! countryBoundaries.isLoaded()) {
                    countryBoundaries.loadKml(assetManager);
                }

                Coordinate[] geoCoordinates = new Coordinate[traceLength + 1];
                for (int i = 0; i < traceLength; i++) {
                    PointF sp = fingerTraceCoordinates.get(i);
                    LatLng ll  = (LatLng) projection.fromPixels(sp.x, sp.y);
                    geoCoordinates[i] = new Coordinate(ll.getLongitude(), ll.getLatitude());
                }

                geoCoordinates[traceLength] = geoCoordinates[0];

                switch (action) {
                    default:
                    case INTERSECT:
                        roiCountries.addAll(countryBoundaries.intersect(geoCoordinates));
                        break;
                    case INCLUDE:
                        roiCountries.addAll(countryBoundaries.containedIn(geoCoordinates));
                        break;
                    case CUTOFF:
                        roiCountries.removeAll(countryBoundaries.intersect(geoCoordinates));
                        break;
                    case EXCLUDE:
                        roiCountries.removeAll(countryBoundaries.containedIn(geoCoordinates));
                        break;
                }

                if (roiCountries.size() > 0) {
                    roiGeometry = countryBoundaries.getCountriesUnionRegion(roiCountries);
                }
                else {
                    if (listener != null) {
                        listener.onRoiCleared();
                    }
                }
            }
            catch (Exception e) {
                Log.d(TAG, "Failed to find outline-to-boundaries intersections!", e);
                return false;
            }

            return true;
        }

        @Override
        protected void onPostExecute(Boolean isSuccess) {
            stopProgress();
            if (listener != null && isSuccess) {
                listener.onRoiChanged(roiGeometry, roiCountries);
            }
        }
    }

    private void startProgress(int messageResourceId) {
        roiProgressMessage.setText(messageResourceId);
        roiProgressWidget.setVisibility(View.VISIBLE);
    }

    private void stopProgress() {
        roiProgressWidget.setVisibility(View.GONE);
    }


    public void test() {
        GeometryFactory geometryFactory = new GeometryFactory();
        MultiPolygon bigger = geometryFactory.createMultiPolygon(new Polygon[]{
                geometryFactory.createPolygon(new Coordinate[]{
                        new Coordinate(0, 0), new Coordinate(0, 2), new Coordinate(2, 2), new Coordinate(2, 0), new Coordinate(0, 0)
                }),
                geometryFactory.createPolygon(new Coordinate[]{
                        new Coordinate(4, 0), new Coordinate(4, 2), new Coordinate(6, 2), new Coordinate(6, 0), new Coordinate(4, 0)
                })
        });

        MultiPolygon smaller = geometryFactory.createMultiPolygon(new Polygon[]{
                geometryFactory.createPolygon(new Coordinate[]{
                        new Coordinate(0.5, 0.5), new Coordinate(0.5, 1.5), new Coordinate(1.5, 1.5), new Coordinate(1.5, 0.5), new Coordinate(0.5, 0.5)
                }),
                geometryFactory.createPolygon(new Coordinate[]{
                        new Coordinate(4.5, 0.5), new Coordinate(4.5, 1.5), new Coordinate(5.5, 1.5), new Coordinate(5.5, 0.5), new Coordinate(4.5, 0.5)
                })
        });

        if (bigger.intersects(smaller)) {
            Log.d(TAG, "Bigger intersects smaller");
        }
        else {
            Log.d(TAG, "Bigger doesn't intersect smaller");
        }

        if (bigger.contains(smaller)) {
            Log.d(TAG, "Bigger contains smaller");
        }
        else {
            Log.d(TAG, "Bigger doesn't contain smaller");
        }

        if (bigger.covers(smaller)) {
            Log.d(TAG, "Bigger covers smaller");
        }
        else {
            Log.d(TAG, "Bigger doesn't cover smaller");
        }
    }
}
