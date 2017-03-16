package com.mogolinc.glide.androidsample;

import android.*;
import android.Manifest;
import android.annotation.TargetApi;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Build;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.FragmentActivity;
import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.SearchView;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.UiSettings;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.mogolinc.glide.apisdk.model.AtmsObj;
import com.mogolinc.glide.cloudsdk.GlideLocation;
import com.mogolinc.glide.cloudsdk.Leg;
import com.mogolinc.glide.cloudsdk.RouteManager;
import com.mogolinc.glide.cloudsdk.Atms;
import com.mogolinc.glide.cloudsdk.Conditions;
import com.mogolinc.glide.cloudsdk.ITrackingUpdate;
import com.mogolinc.glide.cloudsdk.LocationBounds;
import com.mogolinc.glide.cloudsdk.PredictionState;
import com.mogolinc.glide.cloudsdk.ApiException;
import com.mogolinc.glide.cloudsdk.OffRouteException;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import static android.Manifest.*;
import static android.Manifest.permission.*;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.AsyncTask.THREAD_POOL_EXECUTOR;

public class MapsActivity extends FragmentActivity implements OnMapReadyCallback, LocationListener, ITrackingUpdate {

    private static final int MY_PERMISSION_REQUEST_ID = 1;
    private GoogleMap mMap;

    private RouteManager mRouteManager;
    private static String apiKey = "YOUR_GLIDE_API_KEY";

    Timer atmsRequestTimer;
    LatLngBounds mapBounds = null;
    Object mapBoundsLock = new Object();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        atmsObjMap = new HashMap<Integer, Atms>();
        atmsPolylineMap = new HashMap<Integer, Object>();

        if(mRouteManager == null)
            mRouteManager = new RouteManager(apiKey, this);

        mRouteManager.SetCallback(this);
        mRouteManager.Start();

        setContentView(R.layout.activity_maps);
        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        atmsRequestTimer = new Timer();
        atmsRequestTimer.schedule(new TimerTask() {
            public void run() {
                if(fetchRegionTask == null || fetchRegionTask.getStatus() == AsyncTask.Status.FINISHED) {
                    fetchRegionTask = new fetchRegionAsyncTask();
                    fetchRegionTask.executeOnExecutor(THREAD_POOL_EXECUTOR);
                }
            }
        }, 60000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        atmsRequestTimer.cancel();
        mRouteManager.Stop();
    }


    fetchRegionAsyncTask fetchRegionTask;

    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera. In this case,
     * we just add a marker near Sydney, Australia.
     * If Google Play services is not installed on the device, the user will be prompted to install
     * it inside the SupportMapFragment. This method will only be triggered once the user has
     * installed Google Play services and returned to the app.
     */
    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;

        mMap.setOnCameraChangeListener(new GoogleMap.OnCameraChangeListener() {
            @Override
            public void onCameraChange(CameraPosition position) {
                synchronized(mapBoundsLock) {
                    mapBounds = mMap.getProjection().getVisibleRegion().latLngBounds;
                }

                // Cancel existing request if still pending
                if(fetchRegionTask != null && fetchRegionTask.getStatus() != AsyncTask.Status.FINISHED)
                    fetchRegionTask.cancel(true);
                fetchRegionTask = new fetchRegionAsyncTask();
                fetchRegionTask.executeOnExecutor(THREAD_POOL_EXECUTOR);
            }
        });

        subscribeLocationUpdates();
    }

    class fetchRegionAsyncTask extends AsyncTask {
        List<Atms> atmsObjs;
        @Override
        protected Object doInBackground(Object[] params) {
            LocationBounds bounds = new LocationBounds();

            synchronized(mapBoundsLock) {
                if(mapBounds == null)
                    return null;
                Location a = new Location("app");
                a.setLatitude(mapBounds.northeast.latitude);
                a.setLongitude(mapBounds.northeast.longitude);

                Location b = new Location("app");
                b.setLatitude(mapBounds.southwest.latitude);
                b.setLongitude(mapBounds.southwest.longitude);

                bounds.AddLocation(a);
                bounds.AddLocation(b);
            }
            try {
                atmsObjs = mRouteManager.GetAtmsInRegion(bounds);
            } catch (ApiException e) {
                e.printStackTrace();
            }

            return null;
        }

        @Override
        protected void onPostExecute(Object result) {
            if(atmsObjs == null || atmsObjs.size() == 0)
                return;

            List<Integer> changed = new ArrayList<Integer>();
            Integer[] toRemoveArray = new Integer[atmsObjMap.keySet().size()];
            atmsObjMap.keySet().toArray(toRemoveArray);
            List<Integer> toRemove = new LinkedList<>(Arrays.asList(toRemoveArray));
            for(Atms obj : atmsObjs) {
                changed.add(obj.getId());

                if(toRemove.contains(obj.getId()))
                    toRemove.remove(obj.getId());

                // If polyline/annotation exists, update
                // if not, create
                if(atmsObjMap.containsKey(obj.getId())) {
                    UpdateAtmsShape(obj);
                } else {
                    CreateAtmsShape(obj);
                }

                atmsObjMap.put(obj.getId(), obj);
            }

            for(Integer idx : toRemove) {
                // Remove polyline/annotation
                RemoveAtmsShape(atmsObjMap.get(idx));

                // Remove from map
                atmsObjMap.remove(idx);
            }
        }
    }

    Map<Integer, Atms> atmsObjMap;
    Map<Integer, Object> atmsPolylineMap;
    private void CreateAtmsShape(Atms obj) {
        List<LatLng> locs = new ArrayList<LatLng>();


        int c = obj.getAdvspeed() != null ? Color.YELLOW :
                obj.getConditions() != null ? Color.RED :
                        obj.getIntersection() != null ? Color.GREEN :
                                obj.getVolume() != null ? Color.WHITE :
                                        obj.getSpacing() != null ? Color.GRAY :
                                                obj.getZone() != null ? Color.MAGENTA :
                                                        obj.getMeter() != null ? Color.CYAN : Color.BLACK;

        for(GlideLocation l : obj.getGeometry()) {
            locs.add(new LatLng(l.getLatitude(), l.getLongitude()));
        }
        if(obj.getZone() != null && obj.getZone().getFence() != null) {
            Polygon shape = mMap.addPolygon(new PolygonOptions()
                .addAll(locs)
                .strokeWidth(10)
                .fillColor(c));

            atmsPolylineMap.put(obj.getId(), shape);
        } else {
            Polyline line = mMap.addPolyline(new PolylineOptions()
                    .addAll(locs)
                    .width(10)
                    .color(c));

            atmsPolylineMap.put(obj.getId(), line);
        }
    }

    private void UpdateAtmsShape(Atms obj) {

        List<LatLng> locs = new ArrayList<LatLng>();

        for (GlideLocation l : obj.getGeometry()) {
            locs.add(new LatLng(l.getLatitude(), l.getLongitude()));
        }
        if(atmsPolylineMap.get(obj.getId()).getClass() == Polyline.class) {
            Polyline line = (Polyline)atmsPolylineMap.get(obj.getId());
            line.setPoints(locs);
        } else if(atmsPolylineMap.get(obj.getId()).getClass() == Polygon.class) {
            Polygon shape = (Polygon) atmsPolylineMap.get(obj.getId());
            shape.setPoints(locs);
        }
    }

    private void RemoveAtmsShape(Atms obj) {
        if(atmsPolylineMap.get(obj.getId()).getClass() == Polyline.class) {
            Polyline line = (Polyline)atmsPolylineMap.get(obj.getId());

            line.remove();
        } else if(atmsPolylineMap.get(obj.getId()).getClass() == Polygon.class) {
            Polygon shape = (Polygon)atmsPolylineMap.get(obj.getId());
            shape.remove();
        }
        atmsPolylineMap.remove(obj.getId());

    }

    @TargetApi(23)
    private void subscribeLocationUpdates() {
        if (!CheckPermissions()) return;

        // Getting LocationManager object from System Service LOCATION_SERVICE
        LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        // Creating a criteria object to retrieve provider
        Criteria criteria = new Criteria();

        mMap.setMyLocationEnabled(true);
        UiSettings ui = mMap.getUiSettings();
        ui.setMapToolbarEnabled(false);

        // Getting the name of the best provider
        String provider = locationManager.getBestProvider(criteria, true);

        // Getting Current Location
        Location location = locationManager.getLastKnownLocation(provider);

        if (location != null) {
            predictionTask = new PredictionAsyncTask();
            predictionTask.executeOnExecutor(THREAD_POOL_EXECUTOR, location);
            onLocationChanged(location);
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(location.getLatitude(), location.getLongitude()), 14));

        }

        locationManager.requestLocationUpdates(provider, 1000, 0.0f, (LocationListener)this);
    }

    private boolean CheckPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if(ContextCompat.checkSelfPermission(this, ACCESS_FINE_LOCATION) != PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions( this, new String[] {  ACCESS_FINE_LOCATION  },
                        MY_PERMISSION_REQUEST_ID );
                return true;
            }
            if(ContextCompat.checkSelfPermission(this, ACCESS_COARSE_LOCATION) != PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, ACCESS_FINE_LOCATION) != PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, ACCESS_NETWORK_STATE) != PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, INTERNET) != PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, WRITE_EXTERNAL_STORAGE) != PERMISSION_GRANTED ) {
                ActivityCompat.requestPermissions( this, new String[] {  ACCESS_COARSE_LOCATION,
                                                                         ACCESS_FINE_LOCATION,
                                                                         ACCESS_NETWORK_STATE,
                                                                         INTERNET,
                                                                         WRITE_EXTERNAL_STORAGE },
                        MY_PERMISSION_REQUEST_ID );
                return false;
            }
        }
        return true;
    }

    PredictionAsyncTask predictionTask;
    Object predictionTaskLock;
    class PredictionAsyncTask extends AsyncTask<Location, Object, Boolean> {
        String error;

        @Override
        protected Boolean doInBackground(Location... params) {
            while(true) {
                try {
                    Log.d("com.mogolinc", String.format("Requesting prediction for %f,%f, bearing %f", params[0].getLatitude(), params[0].getLongitude(), params[0].getBearing()));
                    mRouteManager.GetPrediction(params[0]);


                    Log.d("com.mogolinc", "Got prediction");
                    return true;
                } catch (ApiException e) {
                    error = e.getMessage();
                    e.printStackTrace();
                }
            }
            //return false;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            if(result)
                DrawPrediction();
            else
                Toast.makeText(getApplicationContext(), error, Toast.LENGTH_LONG);

            predictionTask = null;
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        mRouteManager.UpdateCurrentLocation(location);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if(requestCode == MY_PERMISSION_REQUEST_ID) {
            if(resultCode == RESULT_OK) {
                subscribeLocationUpdates();
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {

    }

    @Override
    public void onProviderEnabled(String provider) {

    }

    @Override
    public void onProviderDisabled(String provider) {

    }

    protected String ConditionToString(Conditions c) {
        switch(c) {
            case ConditionalCarryChains: return "Carry Chains";
            case ConditionalChains: return "Chains Required";
            case Closed: return "Closed";
            case ConditionalChainsGWVR10000: return "Chains for GVW > 10000";
            case ConditionalEmergencyOnly: return "Emergency Services Only";
            case ConstructionLocalTrafficOnly: return "Local traffic only";
            default: return "";
        }
    }

    @Override
    public void onTrackingUpdate(Leg leg, GlideLocation glideLocation) {
        try {
            Log.d("com.mogolinc", "Tracking update at leg " + Integer.toString(leg.Idx) + " " + glideLocation.toString());
            PredictionState state = leg.GetPredictedState(glideLocation);
            double speedMph = 0.621371 * state.Speed * 3.6;
            final String glideSpeed = String.format("Eco Speed: %d MPH", Math.round(speedMph));

            String atmsMsg = "";
            if(state.HasATMS()) {
                for(Atms a : state.atms) {
                    if(a.getVolume() != null) {
                        atmsMsg = String.format("Vol: %d", a.getVolume().getId());
                    } else if(a.getSpacing() != null) {
                        atmsMsg = String.format("Spacing: %d m", a.getSpacing().getId());
                    } else if(a.Conditions != null && a.Conditions.size() > 0) {
                        atmsMsg = String.format("Cond: %s", ConditionToString(a.Conditions.get(0)));
                    } else if(a.getIntersection() != null) {
                        atmsMsg = String.format("Int: %.2f", a.getIntersection().getPercentage());
                    } else if(a.getMeter() != null) {
                        atmsMsg = String.format("Meter: %d", a.getMeter().getId());
                    } else if(a.getZone() != null) {
                        atmsMsg = String.format("Zone: %s", a.getZone().getType());
                    }
                }
            }
            double advisedMph = state.HasSpeedAdvised() ? 0.621371 * state.SpeedAdvised : 0;
            double limitMph = state.HasSpeedLimit() ? 0.621371 * state.SpeedLimit : 0;

            final String limit =
                    state.HasSpeedAdvised() ? String.format("Adv Speed: %d MPH", Math.round(advisedMph)) :
                            state.HasSpeedLimit() ? String.format("Speed Lmt: %d MPH", Math.round(limitMph)) : "";
            final String alert = atmsMsg;

            runOnUiThread(new Runnable() {
                public void run() {
                    TextView speedTextView = (TextView) findViewById(R.id.speedTextView);
                    speedTextView.setText(limit);

                    TextView speedView = (TextView) findViewById(R.id.glideSpeedTextView);
                    speedView.setText(glideSpeed);

                    TextView alertView = (TextView) findViewById(R.id.notificationTextView);
                    alertView.setText(alert);
                }
            });
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onOffRouteUpdate(GlideLocation glideLocation) {
        Log.d("com.mogolinc", "Off route at " + glideLocation.toString() + " legs: " + (mRouteManager.getLegs() != null ? Integer.toString(mRouteManager.getLegs().size()) : "null"));
        runOnUiThread(new Runnable() {
            public void run() {
                if(predictionLine != null)
                    predictionLine.remove();
                predictionLine = null;

                TextView speedView = (TextView) findViewById(R.id.glideSpeedTextView);
                speedView.setText("");
            }
        });

        if(predictionTask == null) {
            predictionTask = new PredictionAsyncTask();
            predictionTask.executeOnExecutor(THREAD_POOL_EXECUTOR, glideLocation);
        }
    }

    Polyline predictionLine;
    private void DrawPrediction() {
        // Use the geometry from route manager to draw the predicted path.
        List<LatLng> locs = new ArrayList<>(mRouteManager.getLocations().size());

        for (Location l : mRouteManager.getLocations()) {
            locs.add(new LatLng(l.getLatitude(), l.getLongitude()));
        }
        Log.d("com.mogolinc", "Drawing prediction for " + Integer.toString(locs.size()) + " points");

        // Remove previous prediction
        if (predictionLine != null)
            predictionLine.remove();
        predictionLine = mMap.addPolyline(new PolylineOptions()
                .addAll(locs)
                .width(15)
                .color(Color.BLUE));

    }
}
