package com.sample;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.location.Location;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentTransaction;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.ToggleButton;

import com.google.android.gms.maps.model.LatLng;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Created by  Crysberry on 08.04.2015.
 */
public class RouteFragment extends DialogFragment
{
    public static final String TAG = RouteFragment.class.getSimpleName();

    private static final String STATE_KEY_DATA_EDITED = TAG + ":STATE_KEY_DATA_EDITED";

    private static final int DIALOG_LOGIN_ERROR = 100;

    private static final int THRESHOLD_TOGGLE_INTERVAL_TIME_MS = 300;

    /**
     */
    public interface Listener
    {
        public void onRouteChanged();
    }

    /**
     */
    Listener listener;

    /**
     */
    @Override
    public void onAttach(Activity activity)
    {
        super.onAttach(activity);

        try {
            listener = (Listener) activity;
        }
        catch (ClassCastException e) {
            Log.w(TAG, activity.toString() + " may implement " + TAG + ".Listener");
        }
    }

    /**
     *
     */
    LinkedList<RoutePoint> routePoints = new LinkedList<>();

    View root;

    TextView routeHeaderTitleView;

    RadioGroup unitSelector;
    RadioButton unitKilometers, unitMiles, unitNauticalMiles;

    ListView routeListView;
    RouteListAdapter routeListAdapter;
    View lastFocusedCoordinateInput;

//    TextView routeTotalView;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
    {
        root = inflater.inflate(R.layout.fragment_route, container, false);

        // window
        Window window = getDialog().getWindow();
        window.setBackgroundDrawable(new ColorDrawable(0xf0ffffff));
        window.requestFeature(Window.FEATURE_NO_TITLE);

        setupViews(savedInstanceState);

        return root;
    }

//    @Override
//    public Dialog onCreateDialog(Bundle savedInstanceState)
//    {
//        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
//
//        LayoutInflater inflater = getActivity().getLayoutInflater();
//        root = inflater.inflate(R.layout.fragment_route, null);
//
//        setupViews();
//
//        builder.setView(root).setInverseBackgroundForced(true);
//
//
//        Dialog dialog = builder.create();
//        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
//
//        return dialog;
//    }


    @Override
    public void onSaveInstanceState(Bundle outState)
    {
        outState.putBoolean(STATE_KEY_DATA_EDITED, true);
        saveEditedRouteState(false);
        super.onSaveInstanceState(outState);
    }

    private void setupViews(Bundle savedInstanceState)
    {
        // title
        routeHeaderTitleView = (TextView) root.findViewById(R.id.RouteHeaderTitle);

        // units: views
        unitSelector = (RadioGroup) root.findViewById(R.id.RouteUnitSelector);
        unitKilometers = (RadioButton) root.findViewById(R.id.RouteUnitKilometers);
        unitMiles = (RadioButton) root.findViewById(R.id.RouteUnitMiles);
        unitNauticalMiles = (RadioButton) root.findViewById(R.id.RouteUnitNauticalMiles);

        // units: initial selection
        switch( Options.getInstance(getActivity()).getDefaultDistanceUnit())
        {
            default:
            case km:
                unitKilometers.setChecked(true);
                break;
            case mi:
                unitMiles.setChecked(true);
                break;
            case nmi:
                unitNauticalMiles.setChecked(true);
                break;
        }

        // units: change listening
        unitSelector.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener()
        {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId)
            {
                switch (checkedId)
                {
                    default:
                    case R.id.RouteUnitKilometers:
                        Options.getInstance(getActivity()).setDefaultDistanceUnit( GeodeticArc.DISTANCE_UNIT.km );
                        break;
                    case R.id.RouteUnitMiles:
                        Options.getInstance(getActivity()).setDefaultDistanceUnit( GeodeticArc.DISTANCE_UNIT.mi );
                        break;
                    case R.id.RouteUnitNauticalMiles:
                        Options.getInstance(getActivity()).setDefaultDistanceUnit( GeodeticArc.DISTANCE_UNIT.nmi );
                        break;
                }

                updateDistances();
            }
        });

        // route total
//        routeTotalView = (TextView) root.findViewById(R.id.RoutDistanceTotal);

        // route
        prepareRouteList(savedInstanceState);
        routeListView = (ListView) root.findViewById(R.id.RoutePointsList);
        routeListView.setAdapter(routeListAdapter = new RouteListAdapter());

        // save button
        root.findViewById(R.id.RouteOkButton).setOnClickListener( new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (saveRoute()) {
                    dismissAllowingStateLoss();
                }
            }
        });

        // close button
        root.findViewById(R.id.RouteCancelButton).setOnClickListener( new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissAllowingStateLoss();
            }
        });

        // clear all button
        root.findViewById(R.id.RouteHeaderClearAllButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (RoutePoint p : routePoints) {
                    p.reset();
                }
                routeListAdapter.notifyDataSetChanged();
            }
        });
    }

    public GeodeticArc.DISTANCE_UNIT getCurrentDistanceUnit()
    {
        switch (unitSelector.getCheckedRadioButtonId())
        {
            default:
            case R.id.RouteUnitKilometers:
                return GeodeticArc.DISTANCE_UNIT.km;
            case R.id.RouteUnitMiles:
                return GeodeticArc.DISTANCE_UNIT.mi;
            case R.id.RouteUnitNauticalMiles:
                return GeodeticArc.DISTANCE_UNIT.nmi;
        }

    }

    /** Create empty items if the source data list is empty.
     */
    private void prepareRouteList(Bundle savedInstanceState)
    {
        // route (restore from temporary storage if the dialog recreated, e.g. on orientation change)
        Route2 route = (null == savedInstanceState)? new Route2(getActivity()): new Route2(getActivity(), Route2.STORAGE_NAME_TEMP);
        route.load();

        // nodes & segments
        List<LatLng> nodes = route.getNodes();
        List<GeodeticArc> segments = route.getSegments();

        if (nodes.size() > 0) {
            routePoints.add(new RoutePoint(nodes.get(0).latitude, nodes.get(0).longitude));
        }

        for (int i = 1, s = nodes.size(); i < s; i++) {
            routePoints.add(new RoutePoint(nodes.get(i).latitude, nodes.get(i).longitude, segments.get(i - 1)));
        }

        // empty source data
        if (routePoints.size() == 0) {
            Location currentLocation = SatbeamsLocation.getInstance().getCurrentLocation();
            routePoints.add( new RoutePoint(currentLocation.getLatitude(), currentLocation.getLongitude()) );
            routePoints.add( new RoutePoint() );
        }

        updateDistanceTotal();
    }

    private void updateDistances()
    {
        if (null == routeListAdapter)
            return;

        // save last input in case it wasn't saved
        if (lastFocusedCoordinateInput != null) {
            View z = lastFocusedCoordinateInput;
            lastFocusedCoordinateInput = null;
            commitCoordinateInput((TextView) z);
        }

        for (int i = 1, s = routePoints.size(); i < s; i++)
        {
            RoutePoint point = routePoints.get(i);
            Log.d(TAG, "Segment #" + (i - 1) + " was " + (point.segment instanceof GreatCircleArc? "Great Circle": "Rhumb"));
            point.setSegmentFrom(routePoints.get(i - 1));
            Log.d(TAG, "Segment #" + (i - 1) + " became " + (point.segment instanceof GreatCircleArc ? "Great Circle" : "Rhumb"));
        }

        updateDistanceTotal();

        routeListAdapter.notifyDataSetChanged();
    }

    private void updateDistanceTotal()
    {
        if (null == routeHeaderTitleView)
            return;

        if (lastFocusedCoordinateInput != null) {
            View z = lastFocusedCoordinateInput;
            lastFocusedCoordinateInput = null;
            commitCoordinateInput((TextView) z);
        }

        double totalLength = 0.0;
        for (int i = 1, s = routePoints.size(); i < s; i++)
        {
            RoutePoint point = routePoints.get(i);
            if (null == point.segment)
                continue;

            totalLength += point.segment.getLength( getCurrentDistanceUnit());
        }

        if (totalLength > 0)
            setRouteTotalText(formatDistanceFor(totalLength));
        else
            setRouteTotalText("");
    }

    private void setRouteTotalText(String totalText) {
        routeHeaderTitleView.setText( getString(R.string.route_form_title) + (TextUtils.isEmpty(totalText)? "": "  " + totalText));
    }

    private class RoutePoint
    {
        Double latitude, longitude;
        GeodeticArc segment;

        public RoutePoint() {
        }

        public RoutePoint(Double latitude, Double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }

        public RoutePoint(Double latitude, Double longitude, GeodeticArc segment) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.segment = segment;
        }

        public boolean isSet() {
            return (latitude != null && longitude != null);
        }

        public LatLng getLatLng() {
            return new LatLng(latitude, longitude);
        }

        public void reset() {
            latitude = longitude = null;
            segment = null;
        }

        public void setSegmentFrom(RoutePoint begin, Route2.GEOMETRY geometry)
        {
            if (null == begin || ! begin.isSet() || ! isSet()) {
                segment = null;
                return;
            }

            Log.d(TAG, "Segment (" + begin.latitude + "," + begin.longitude + ":" + latitude + "," + longitude + ") is (re)created as " + (geometry == Route2.GEOMETRY.GREAT_CIRCLE? "Great Circle": "Rhumb"));

            segment = geometry == Route2.GEOMETRY.GREAT_CIRCLE ?
                new GreatCircleArc(new LatLng(begin.latitude, begin.longitude), new LatLng(latitude, longitude)):
                new RhumbArc(new LatLng(begin.latitude, begin.longitude), new LatLng(latitude, longitude));
        }

        public void setSegmentFrom(RoutePoint begin)
        {
            if (null == begin || ! begin.isSet() || ! isSet()) {
                segment = null;
                return;
            }

            if (null == segment) {
                Log.d(TAG, "Segment " + begin.latitude + "," + begin.longitude + ":" + latitude + "," + longitude + ") created (Great Circle by default).");
            }
            else {
                Log.d(TAG, "Segment (" + begin.latitude + "," + begin.longitude + ":" + latitude + "," + longitude + ") is set to be " + (segment instanceof GreatCircleArc? "Great Circle": "Rhumb"));
            }

            segment = (null == segment || segment instanceof GreatCircleArc) ?
                    new GreatCircleArc(new LatLng(begin.latitude, begin.longitude), new LatLng(latitude, longitude)):
                    new RhumbArc(new LatLng(begin.latitude, begin.longitude), new LatLng(latitude, longitude));
        }

        public void toggleSegmentGeometry()
        {
            if (null == segment)
                return;

            Log.d(TAG, "Segment to " + latitude + "," + longitude + ") is TOGGLED to be " + (segment instanceof GreatCircleArc? "Rhumb": "Great Circle"));

            segment = (segment instanceof GreatCircleArc) ?
                    new RhumbArc(segment.getBeginDegrees(), segment.getEndDegrees()):
                    new GreatCircleArc(segment.getBeginDegrees(), segment.getEndDegrees());
        }
    }

    private class RouteItemHolder
    {
        int position;

        TextView sequenceNumberView;
        ToggleButton geometryToggle;
        EditText latEdit, lonEdit;
        TextView distanceView;
        ImageButton addButton, clearButton;

        public RouteItemHolder(int position, final View view)
        {
            this.position = position;

            sequenceNumberView = (TextView) view.findViewById(R.id.RouteItemNumber);

            geometryToggle = (ToggleButton) view.findViewById(R.id.ItemGeometryToggleButton);
            geometryToggle.setOnCheckedChangeListener(onRouteItemGeometryToggle);
            geometryToggle.setVisibility(View.VISIBLE);

            latEdit = (EditText) view.findViewById(R.id.RouteItemLatitude);
            latEdit.setOnEditorActionListener(coordinateEditorActionListener);
            latEdit.setOnFocusChangeListener(coordinateEditFocusChangeListener);

            lonEdit = (EditText) view.findViewById(R.id.RouteItemLongitude);
            lonEdit.setOnEditorActionListener(coordinateEditorActionListener);
            lonEdit.setOnFocusChangeListener(coordinateEditFocusChangeListener);

            distanceView = (TextView) view.findViewById(R.id.RouteItemDistance);

            // add button
            addButton = (ImageButton) view.findViewById(R.id.ItemAddButton);
            addButton.setOnClickListener(onRouteItemAddListener);

            // clear
            clearButton = (ImageButton) view.findViewById(R.id.ItemClearButton);
            clearButton.setOnClickListener(onRouteItemClearedListener);
        }

        public void setGeometryToggle(boolean doCheck) {
            geometryToggle.setOnCheckedChangeListener(null);
            geometryToggle.setChecked(doCheck);
            geometryToggle.setOnCheckedChangeListener(onRouteItemGeometryToggle);
        }
    }

    /** ... to find list position correspondent to given latitude/longitude edit text or clear button view, etc.
     *
     */
    private int getListItemPositionFor(View listItemSubview)
    {
//        RouteItemHolder holder = (RouteItemHolder) ((View) listItemSubview.getParent()).getTag();
//        return holder.position;

        try {
            return routeListView.getPositionForView(listItemSubview);
        }
        catch (Exception x) {
            return ListView.INVALID_POSITION;
        }
    }

    private OnRouteItemAdd onRouteItemAddListener = new OnRouteItemAdd();

    /** Add Route Segment
     *
     */
    private class OnRouteItemAdd implements View.OnClickListener
    {
        @Override
        public void onClick(View addButton)
        {
            int position = getListItemPositionFor(addButton);
            if (position < 0 || position > routePoints.size() - 1)
                return;

            routePoints.add(position + 1, new RoutePoint());

            if (position + 2 < routePoints.size()) {
                routePoints.get(position + 1).segment = null;
//                ((RouteItemHolder) ((View) addButton.getParent()).getTag()).geometryToggle.setVisibility(View.INVISIBLE);
            }

            updateDistances();
            routeListAdapter.notifyDataSetChanged();
        }
    }

    private OnRouteItemGeometryChanged onRouteItemGeometryToggle = new OnRouteItemGeometryChanged();

    private volatile Long lastGeometryToggleTimeMs;

    /** Route Mode
     *
     */
    private class OnRouteItemGeometryChanged implements CompoundButton.OnCheckedChangeListener
    {
        @Override
        public void onCheckedChanged(CompoundButton geometryToggle, boolean isChecked)
        {
            int position = getListItemPositionFor(geometryToggle);

            // work around to address extra toggles, on other list items
            if (null == lastGeometryToggleTimeMs) {
                lastGeometryToggleTimeMs = System.currentTimeMillis();
            }
            else
            {
                if (System.currentTimeMillis() - lastGeometryToggleTimeMs < THRESHOLD_TOGGLE_INTERVAL_TIME_MS) {
                    //Log.d(TAG, "Too quick geometry toggle at # " + position);
                    return;
                }

                lastGeometryToggleTimeMs = System.currentTimeMillis();
                //Log.d(TAG, "Geometry toggle at # " + position);
            }

            if (position < 0 || position > routePoints.size() - 1)
                return;

            RoutePoint p = routePoints.get(position);
            if (null == p.segment)
                return;

            routePoints.get(position).toggleSegmentGeometry();
            routeListAdapter.notifyDataSetChanged();
        }
    }

    private OnRouteItemCleared onRouteItemClearedListener = new OnRouteItemCleared();

    /** Delete Route Segment
     *
     */
    private class OnRouteItemCleared implements View.OnClickListener
    {
        @Override
        public void onClick(View clearButtonView)
        {
            int position = getListItemPositionFor(clearButtonView);
            if (position < 0 || position > routePoints.size() - 1)
                return;

            if (position < 2 && routePoints.size() < 3) {
                RoutePoint p = routePoints.get(position);
                p.reset();
            }
            else {
                routePoints.remove(position);
            }

            updateDistances();
            routeListAdapter.notifyDataSetInvalidated();
        }
    }

    /** Route List Adapter
     *
     */
    private class RouteListAdapter extends ArrayAdapter<RoutePoint>
    {
        LayoutInflater inflater;

        public RouteListAdapter() {
            super(getActivity(), R.layout.item_route, routePoints);
            inflater = getActivity().getLayoutInflater();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent)
        {
            RouteItemHolder holder;

            if (null == convertView) {
                convertView = inflater.inflate(R.layout.item_route, null);
                holder = new RouteItemHolder(position, convertView);
                convertView.setTag(holder);
            }
            else {
                holder = (RouteItemHolder) convertView.getTag();
                holder.position = position;
            }

            RoutePoint item = getItem(position);

            // sequence number
            holder.sequenceNumberView.setText(String.format("%d", position + 1));

            // geometry type
            if (null == item.segment || 0 == position) {
//                Log.d(TAG, "HIDE @" + describe(position, holder, item));
//                holder.geometryToggle.setVisibility(View.INVISIBLE);
                holder.setGeometryToggle(false);
            }
            else {
//                Log.d(TAG, "SHOW @" + describe(position, holder, item));
//                holder.geometryToggle.setVisibility(View.VISIBLE);
                holder.setGeometryToggle(item.segment != null && item.segment instanceof RhumbArc);
            }

            // coordinates
            if (! item.isSet()) {
                holder.latEdit.setText("");
                holder.lonEdit.setText("");
            }
            else {
                holder.latEdit.setText( String.valueOf(item.latitude) );
                holder.lonEdit.setText( String.valueOf(item.longitude));
            }

            // distance
            if (position == 0 || ! item.isSet()) {
                holder.distanceView.setText("");
            }
            else
            {
                if (null == item.segment) {
                    item.setSegmentFrom( getItem(position - 1), Route2.GEOMETRY.GREAT_CIRCLE);
                }

                holder.distanceView.setText( formatDistanceFor(item));
            }

            // IME option
            holder.lonEdit.setImeOptions(position < getCount() - 1? EditorInfo.IME_ACTION_NEXT: EditorInfo.IME_ACTION_DONE);

            // distance total
            updateDistanceTotal();

            // clear button
            holder.clearButton.setTag(position);

            return convertView;
        }

        private String describe(int position, RouteItemHolder holder, RoutePoint item) {
            return String.valueOf(position) +
                    " (" + (null == item.latitude ? "null" : String.valueOf(item.latitude)) + "/" + holder.latEdit.getText().toString() +
                    ", " + (null == item.longitude ? "null" : String.valueOf(item.longitude)) + "/" + holder.lonEdit.getText().toString() +
                    ")";
        }
    }

    private CoordinateEditorActionListener coordinateEditorActionListener = new CoordinateEditorActionListener();

    private class CoordinateEditorActionListener implements TextView.OnEditorActionListener {
        @Override
        public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                commitCoordinateInput(view);
                Log.d(TAG, "Saved coordinate editing.");
                return false;
            }
            Log.d(TAG, "Skipped saving of the editing.");
            return false;
        }
    }

    private CoordinateEditFocusChangeListener coordinateEditFocusChangeListener = new CoordinateEditFocusChangeListener();

    private class CoordinateEditFocusChangeListener implements View.OnFocusChangeListener
    {
        @Override
        public void onFocusChange(View view, boolean hasFocus)
        {
            if (hasFocus) {
                Log.d(TAG, "Entered a coordinate edit.");
                lastFocusedCoordinateInput = view;
                ((EditText) view).setTextColor(Color.BLACK);
                return;
            }

            Log.d(TAG, "Leaved a coordinate edit.");
            lastFocusedCoordinateInput = null;
            commitCoordinateInput((TextView) view);
        }
    }

    private boolean commitCoordinateInput(TextView view)
    {
        Log.d(TAG, "Committing coordinate input.");

        int position = getListItemPositionFor(view);
        if (position < 0 || position > routePoints.size() - 1)
            return false;

        RoutePoint item = routePoints.get(position);

        RouteItemHolder holder = (RouteItemHolder) ((View) view.getParent()).getTag();
        RouteItemHolder nextItemHolder = findViewHolderForPosition(position + 1);

        boolean isLatitude = (view.getId() == R.id.RouteItemLatitude);

        // input
        String input = view.getText().toString();
        if (TextUtils.isEmpty(input))
            return false;

        boolean isValidInput = isLatitude? isValidLatitudeInput(input): isValidLongitudeInput(input);
        if (! isValidInput)
        {
            if (isLatitude)
                item.latitude = null;
            else
                item.longitude = null;

            view.setTextColor(Color.RED);

//            holder.geometryToggle.setVisibility(View.INVISIBLE);
//            if (nextItemHolder != null)
//                nextItemHolder.geometryToggle.setVisibility(View.INVISIBLE);

            return false;
        }

        view.setTextColor(Color.BLACK);

        // route point
        if (isLatitude)
            item.latitude = Double.valueOf(input);
        else
            item.longitude = Double.valueOf(input);

        // this segment length
        if (! item.isSet())
            return true; // won't evaluate distance 'cause second coordinate isn't set, though this input is ok

        if (position > 0 && item.isSet())
        {
            item.setSegmentFrom(routePoints.get(position - 1));
            holder.distanceView.setText(formatDistanceFor(item));
//            holder.geometryToggle.setVisibility(View.VISIBLE);
        }

        // next segment length
        if (position + 1 < routePoints.size())
        {
            RoutePoint nextItem = routePoints.get(position + 1);
            nextItem.setSegmentFrom(item);
            if (item.segment != null)
            {
                if (nextItemHolder != null) {
                    nextItemHolder.distanceView.setText(formatDistanceFor(nextItem));
//                    nextItemHolder.geometryToggle.setVisibility(View.VISIBLE);
                }
            }
        }

        updateDistanceTotal();
        return true;
    }

    private RouteItemHolder findViewHolderForPosition(int position)
    {
        if (position < 0 && position >= routePoints.size())
            return null;

        for (int i = 0, s = routeListView.getChildCount(); i < s; i++) {
            RouteItemHolder testHolder = (RouteItemHolder) routeListView.getChildAt(i).getTag();
            if (testHolder.position == position) {
                return testHolder;
            }
        }
        return null;
    }

    private boolean saveRoute() {
        return saveRoute(false, true);
    }

    private boolean saveEditedRouteState(boolean showErrorDialog) {
        return saveRoute(true, false);
    }

    private boolean saveRoute(boolean saveTemporaryCopy, boolean showErrorDialog)
    {
        // save last input
        if (lastFocusedCoordinateInput != null) {
            commitCoordinateInput((TextView) lastFocusedCoordinateInput);
            lastFocusedCoordinateInput = null;
        }

        // collect data
        ArrayList<LatLng> export = new ArrayList<>();

        for (RoutePoint p : routePoints)
        {
            if (null == p.latitude && null == p.longitude) {
                continue;
            }

            if (null == p.latitude || null == p.longitude) {
                // coordinate invalid or missed
                if (showErrorDialog)
                    showError(getString(R.string.route_form_cannot_save_invalid_data));
                return false;
            }

            export.add(new LatLng(p.latitude, p.longitude));
        }

        if (export.size() == 1) {
            if (showErrorDialog)
                showError(getString(R.string.route_form_cannot_save_not_enough_data));
            return false;
        }

        // store
        Route2 route = saveTemporaryCopy ? new Route2(getActivity(), Route2.STORAGE_NAME_TEMP): new Route2(getActivity());

        if (export.size() > 1)
        {
            if (routePoints.size() > 0) {
                route.add(routePoints.get(0).getLatLng());
            }

            for (int i = 1, s = routePoints.size(); i < s; i++) {
                RoutePoint p = routePoints.get(i);
                if (!p.isSet() || null == p.segment)
                    continue;

                route.add(p.getLatLng(), p.segment);
            }
        }

        route.save();

        // notify
        if (listener != null) {
            listener.onRouteChanged();
        }

        return true;
    }

    private String formatDistanceFor(RoutePoint routePoint) {
        GeodeticArc.DISTANCE_UNIT unit = getCurrentDistanceUnit();
        return null == routePoint.segment? "":
                String.format("%.2f %s", routePoint.segment.getLength(unit), unit.name());
    }

    private String formatDistanceFor(Double length) {
        return null == length? "": String.format("%.2f %s", length, getCurrentDistanceUnit().name());
    }

    private boolean isValidLatitudeInput(String inputSource) {
        try {
            Double d = Double.valueOf(inputSource);
            if (d < -90.0 || d > 90.0)
                return false;
        }
        catch (NumberFormatException x) {
            return false;
        }
        return true;
    }

    private boolean isValidLongitudeInput(String inputSource) {
        try {
            Double d = Double.valueOf(inputSource);
            if (d < -180.0 || d > 180.0)
                return false;
        }
        catch (NumberFormatException x) {
            return false;
        }
        return true;
    }

    private void showError(String message)
    {
        FragmentTransaction transaction = getFragmentManager().beginTransaction();

        DialogFragment errorDialog = MessageDialogFragment.newInstance(
                getString(R.string.route_form_error_title), message);
        errorDialog.setTargetFragment(RouteFragment.this, DIALOG_LOGIN_ERROR);

        errorDialog.show(transaction, "ERROR_DIALOG");
    }
}
