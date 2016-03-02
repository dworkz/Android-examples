package com.sample.dal;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.io.ParseException;
import com.vividsolutions.jts.io.WKTReader;

import java.util.Arrays;
import java.util.List;

/** Region Of Interest with persistence. Provides also list of country names if it
 * is a union of them.
 *
 * Created by Crysberry on 8/10/15.
 */
public class RegionOfInterest
{
    private static final String TAG = RegionOfInterest.class.getSimpleName();

    private final static String STORAGE_FILE_NAME = "ROI";
    private final static String STORAGE_TEMP_FILE_NAME = "ROI_temporary";

    private final static String STORAGE_KEY_COUNTRY_NAMES = "STORAGE_KEY_COUNTRY_NAMES";
    private final static String STORAGE_KEY_ROI = "STORAGE_KEY_ROI";
    private static final String STORAGE_KEY_IS_ROI_ACTIVE = "STORAGE_KEY_IS_ROI_ACTIVE";

    private final String storageFileName;

    private Geometry roiGeometry;

    private List<String> roiCountries;

    private boolean isRestored = false;

    private WKTReader wktReader;

    /**
     *
     */
    public RegionOfInterest() {
        this.storageFileName = STORAGE_FILE_NAME;
    }

    /**
     *
     * @param isTemporary
     */
    public RegionOfInterest(boolean isTemporary) {
        if (isTemporary)
            this.storageFileName = STORAGE_TEMP_FILE_NAME;
        else
            this.storageFileName = STORAGE_FILE_NAME;
    }

    public void save(Geometry roiGeometry, List<String> countryNames, Context context) {
        save(roiGeometry, countryNames, context, storageFileName);
    }

    /**
     */
    private void save(Geometry roiGeometry, List<String> countryNames, Context context, String aStorageFileName)
    {
        String roiGeometryWkt = (null == roiGeometry)? null: roiGeometry.toText();
        String countryNameCsv = (null == countryNames)? null: TextUtils.join(",", countryNames);

        context.getSharedPreferences(aStorageFileName, Context.MODE_PRIVATE).edit()
                .putString(STORAGE_KEY_COUNTRY_NAMES, countryNameCsv)
                .putString(STORAGE_KEY_ROI, roiGeometryWkt)
                .commit();

        this.roiGeometry = roiGeometry;
        this.roiCountries = countryNames;
    }


    public void restore(Context context) {
        restore(context, storageFileName);
    }

    private void restore(Context context, String aStorageFileName)
    {
        SharedPreferences store = context.getSharedPreferences(aStorageFileName, Context.MODE_PRIVATE);

        // roi
        String roiGeometryWkt = store.getString(STORAGE_KEY_ROI, null);
        if (TextUtils.isEmpty(roiGeometryWkt)) {
            roiGeometry = null;
        }
        else
        {
            if (null == wktReader) {
                wktReader = new WKTReader();
            }

            try
            {
                roiGeometry = wktReader.read(roiGeometryWkt);
            }
            catch (ParseException x) {
                Log.e(TAG, "Error parsing ROI WKT", x);
                roiGeometry = null;
            }
        }

        // country names
        String countryNameCsv = context.getSharedPreferences(storageFileName,
                Context.MODE_PRIVATE).getString(STORAGE_KEY_COUNTRY_NAMES, null);

        if (TextUtils.isEmpty(countryNameCsv)) {
            roiCountries = null;
        }
        else {
            roiCountries = Arrays.asList(TextUtils.split(countryNameCsv, ","));
        }

        isRestored = true;
    }

    public void reset(Context context) {
        context.getSharedPreferences(storageFileName, Context.MODE_PRIVATE).edit()
                .putString(STORAGE_KEY_ROI, null)
                .putString(STORAGE_KEY_COUNTRY_NAMES, null)
                .commit();
        this.roiGeometry = null;
        this.roiCountries = null;
    }


    public boolean isEmpty() {
        return null == roiGeometry || roiGeometry.isEmpty();
    }

    public List<String> getCountryNames(Context context)
    {
        if (! isRestored) {
            restore(context);
        }
        return roiCountries;
    }

    public Geometry getGeometry(Context context) {
        if (! isRestored) {
            restore(context);
        }
        return roiGeometry;
    }

    public boolean isVisibleFrom(Satellite satellite, Context context) {
        Double minElevation = null;
        for (Coordinate roiVertex : roiGeometry.getCoordinates()) {
            double elevation = satellite.getElevationAtPosition(roiVertex.y, roiVertex.x);
            if (null == minElevation || minElevation > elevation)
                minElevation = elevation;
        }
        if (minElevation < 0)
            return false;
        return true;
    }

    public void copyFromTemporary(Context context) {
        restore(context, STORAGE_TEMP_FILE_NAME);
        save(roiGeometry, roiCountries, context);
    }

    public void copyToTemporary(Context context) {
        save(roiGeometry, roiCountries, context, STORAGE_TEMP_FILE_NAME);
    }

    public boolean isActive(Context context) {
        return context.getSharedPreferences(STORAGE_FILE_NAME, Context.MODE_PRIVATE).getBoolean(STORAGE_KEY_IS_ROI_ACTIVE, false);
    }

    public void activate(Context context) {
        context.getSharedPreferences(STORAGE_FILE_NAME, Context.MODE_PRIVATE).edit().putBoolean(STORAGE_KEY_IS_ROI_ACTIVE, true).commit();
    }

    public void deactivate(Context context) {
        context.getSharedPreferences(STORAGE_FILE_NAME, Context.MODE_PRIVATE).edit().putBoolean(STORAGE_KEY_IS_ROI_ACTIVE, false).commit();
    }
}
