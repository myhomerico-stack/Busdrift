package dk.busdrift.navigator;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Kort, OSRM-vejvisning og live nedtælling for næste stop. */
public final class NavigationActivity extends Activity implements LocationListener {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private RouteMapView map;
    private TextView instruction,eta,distance,status;
    private LocationManager locations;
    private Location lastLocation;
    private String key,token,base,address;
    private long lastRouteAt,receivedAt;
    private int remainingSeconds=-1;
    private boolean fetching;
    private final Runnable ticker=new Runnable(){@Override public void run(){
        if(remainingSeconds>=0){long seconds=Math.max(0,remainingSeconds-(SystemClock.elapsedRealtime()-receivedAt)/1000);
            eta.setText(String.format(Locale.forLanguageTag("da-DK"),"%02d:%02d:%02d til næste stop",seconds/3600,(seconds/60)%60,seconds%60));}
        main.postDelayed(this,1000);
    }};

    @Override public void onCreate(Bundle state){super.onCreate(state);
        key=getIntent().getStringExtra("key");base=getIntent().getStringExtra("base");token=getIntent().getStringExtra("token");address=getIntent().getStringExtra("address");
        if(key==null||base==null||token==null||address==null){finish();return;}
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(14,12,14,12);root.setBackgroundColor(Color.rgb(242,247,245));setContentView(root);
        instruction=label("Vejvisning til næste stop",23,true);root.addView(instruction);
        root.addView(label(address,16,false));
        eta=label("Afventer GPS …",21,true);root.addView(eta);
        distance=label("",16,false);root.addView(distance);
        map=new RouteMapView(this);root.addView(map,new LinearLayout.LayoutParams(-1,0,1));
        status=label("Følg vejen og hold øje med trafikken. Kortets rute tager ikke højde for bussens mål.",14,false);root.addView(status);
        Button arrived=new Button(this);arrived.setText("Jeg er ankommet til stoppet");root.addView(arrived);
        arrived.setOnClickListener(v->{setResult(RESULT_OK);finish();});
        Button back=new Button(this);back.setText("Tilbage til dagens stop");root.addView(back);back.setOnClickListener(v->finish());
        locations=(LocationManager)getSystemService(LOCATION_SERVICE);main.post(ticker);
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},40);
        else listen();
    }
    private TextView label(String value,int size,boolean bold){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(Color.rgb(18,56,62));view.setPadding(3,5,3,5);if(bold)view.setTypeface(null,1);return view;}
    private void listen(){
        try {
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED){
                locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,3000,4,this);
                Location known=locations.getLastKnownLocation(LocationManager.GPS_PROVIDER);if(known!=null)onLocationChanged(known);
            }
            if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED){
                locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,5000,15,this);
                if(lastLocation==null){Location known=locations.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);if(known!=null)onLocationChanged(known);}
            }
        }catch(Exception e){status.setText("Aktivér telefonens placering for at se vejvisning.");}
    }
    @Override public void onRequestPermissionsResult(int code,String[] p,int[] grants){super.onRequestPermissionsResult(code,p,grants);if(code==40){
        boolean granted=false;for(int g:grants)if(g==PackageManager.PERMISSION_GRANTED)granted=true;
        if(granted)listen();else status.setText("GPS-tilladelse mangler. Kortnavigation kræver adgang til placering.");
    }}
    @Override public void onLocationChanged(Location loc){
        if(loc.getAccuracy()>150){if(lastLocation==null)status.setText("Venter på præcis GPS-position …");return;}
        lastLocation=loc;map.position(loc.getLatitude(),loc.getLongitude());
        if(!fetching&&(lastRouteAt==0||SystemClock.elapsedRealtime()-lastRouteAt>25000))fetchRoute(loc);
    }
    private void fetchRoute(Location loc){
        fetching=true;lastRouteAt=SystemClock.elapsedRealtime();
        worker.execute(()->{
            try {
                JSONObject body=new JSONObject();body.put("latitude",loc.getLatitude());body.put("longitude",loc.getLongitude());body.put("key",key);
                HttpURLConnection con=(HttpURLConnection)new URL(base+"navigator-api.php?action=navigate").openConnection();
                con.setConnectTimeout(12000);con.setReadTimeout(22000);con.setRequestMethod("POST");con.setDoOutput(true);
                con.setRequestProperty("Authorization","Bearer "+token);con.setRequestProperty("Accept","application/json");con.setRequestProperty("Content-Type","application/json; charset=utf-8");
                try(OutputStream out=con.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}
                try(InputStream in=con.getInputStream();ByteArrayOutputStream result=new ByteArrayOutputStream()){
                    byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){result.write(buf,0,n);if(result.size()>4000000)throw new Exception("Rutesvaret er for stort.");}
                    JSONObject response=new JSONObject(result.toString("UTF-8"));
                    if(response.has("error"))throw new Exception(response.optString("error"));
                    main.post(()->updateRoute(response));
                }finally{con.disconnect();}
            }catch(Exception e){main.post(()->status.setText("Ruten kunne ikke opdateres: "+e.getMessage()));}
            finally{main.post(()->fetching=false);}
        });
    }
    private void updateRoute(JSONObject data){
        if(isFinishing())return;
        remainingSeconds=data.optInt("duration",-1);receivedAt=SystemClock.elapsedRealtime();
        map.route(data.optJSONArray("geometry"));
        int meters=data.optInt("distance");distance.setText(meters<1000?meters+" m tilbage":String.format(Locale.forLanguageTag("da-DK"),"%.1f km tilbage",meters/1000.0));
        JSONArray steps=data.optJSONArray("steps");JSONObject next=null;double lead=0;
        if(steps!=null)for(int i=0;i<steps.length();i++){
            JSONObject s=steps.optJSONObject(i);if(s==null)continue;
            if("depart".equals(s.optString("type"))){lead=s.optDouble("distance");continue;}
            next=s;break;
        }
        if(next==null){instruction.setText("Fortsæt til "+address);}
        else{String type=next.optString("type");String modifier=next.optString("modifier");
            String direction="turn".equals(type)?("left".equals(modifier)?"Drej til venstre":"right".equals(modifier)?"Drej til højre":"Drej"):
                "roundabout".equals(type)?"Kør ind i rundkørslen": "arrive".equals(type)?"Ankom til stoppet": "Fortsæt";
            String road=next.optString("road");instruction.setText(direction+(road.isEmpty()?"":" ad "+road)+(lead>0?" om "+(int)lead+" m":""));}
        status.setText("Ruten opdateres, når du kører. Følg altid skilte og bussens begrænsninger.");
    }
    @Override protected void onDestroy(){
        main.removeCallbacks(ticker);if(locations!=null)locations.removeUpdates(this);worker.shutdownNow();if(map!=null)map.close();super.onDestroy();
    }
}
