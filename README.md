# GlideAndroidSample
Sample application for the Glide Android SDK.

# Adding the Glide Android SDK to your project
1) Sign up for an API key at http://mogolinc.com/development.html.
2) Add maven.mogolinc.com as a repo to your project
3) Add glide-cloudsdk as a dependency

# Using the Glide Android SDK

Glide can either provide raw data, or route prediction and tracking. 

## Raw Data

To retrieve a set of events in a bounding box, create a RouteManager and use GetAtmsInRegion:

```java
RouteManager manager = new RouteManager(apiKey, getApplicationContext());
LocationBounds bounds = new LocationBounds();
Location a = new Location("test");
Location b = new Location("test");
a.setLatitude(45.5);
a.setLongitude(-122.5);
b.setLatitude(45.3);
b.setLongitude(-122.3);
List<AtmsObj> events = manager.GetAtmsInRegion(bounds);
```

Each AtmsObj has geometry describing a polygon (if it is a zone, checked by testing if getZone() is null), or a line, and relevant information about the event.

## Route Prediction and tracking
RouteManager can estimate your path based on current lat/lng and bearing, or you can provide a route. 
The estimation is done by calling RouteManager.GetPrediction(...). GetPrediction gets the terrain, speed limit, and any events
relevant to the route (i.e. the objects returned by GetAtmsInRegion), and computes an energy efficient speed trajectory taking these into account.

You can view the resulting prediction in RouteManager.Legs. 

RouteManager can also track the device along the route. To do this, provide a callback object that implements ITrackingUpdate to RouteManager.setCallback().
Then, start the tracking via RouteManager.start(). If you want to stop tracking, call RouteManager.stop().

Every second, RouteManager will call ITrackingUpdate.onTrackingUpdate() with the current Leg and current location (interpolated if there has not been an update).
If the device has gone off route, RouteManager will call ITrackingUpdate.onOffRouteUpdate(), and will cease to make any further callbacks. You will need to call GetPrediction()
again with the current position to recalculate the route.
