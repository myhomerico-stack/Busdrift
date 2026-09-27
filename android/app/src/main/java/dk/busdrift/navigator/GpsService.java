package dk.busdrift.navigator;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GpsService extends Service implements LocationListener {
    public static volatile boolean tracking=false;
    private LocationManager manager;
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private String base,token;
    private int tourId;
    private long lastSent=0;
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        base=intent.getStringExtra("base");token=intent.getStringExtra("token");tourId=intent.getIntExtra("tourId",0);
        if(base==null||token==null||tourId<1){stopSelf();return START_NOT_STICKY;}
        NotificationManager notifications=getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("gps","GPS under turen",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification note=new Notification.Builder(this,"gps").setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Busdrift deler GPS").setContentText("Tur "+tourId+" · Åbn appen for at stoppe deling").setContentIntent(open).setOngoing(true).build();
        if(Build.VERSION.SDK_INT>=29)startForeground(12,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);else startForeground(12,note);
        if(manager!=null)try{manager.removeUpdates(this);}catch(Exception ignored){}
        manager=(LocationManager)getSystemService(LOCATION_SERVICE);
        try {
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,30000,20,this);
            if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED)
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,30000,20,this);
            tracking=true;
        } catch(SecurityException | IllegalArgumentException e) {stopSelf();}
        return START_NOT_STICKY;
    }
    @Override public void onLocationChanged(Location loc) {
        if(loc.getAccuracy()<0||loc.getAccuracy()>300||SystemClock.elapsedRealtime()-lastSent<30000)return;
        lastSent=SystemClock.elapsedRealtime();String endpoint=base,auth=token;int id=tourId;
        network.execute(()->{
            try {
                JSONObject data=new JSONObject();data.put("tourId",id);data.put("latitude",loc.getLatitude());data.put("longitude",loc.getLongitude());data.put("accuracy",loc.getAccuracy());
                HttpURLConnection conn=(HttpURLConnection)new URL(endpoint+"navigator-v3.php?action=position").openConnection();
                conn.setConnectTimeout(8000);conn.setReadTimeout(8000);conn.setRequestMethod("POST");conn.setDoOutput(true);
                conn.setRequestProperty("Authorization","Bearer "+auth);conn.setRequestProperty("Content-Type","application/json; charset=utf-8");
                try(OutputStream out=conn.getOutputStream()){out.write(data.toString().getBytes(StandardCharsets.UTF_8));}
                int response=conn.getResponseCode();InputStream input=response>=400?conn.getErrorStream():conn.getInputStream();
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();if(input!=null)try(InputStream in=input){byte[] buffer=new byte[512];int n;
                    while((n=in.read(buffer))!=-1&&bytes.size()<2048)bytes.write(buffer,0,n);}
                conn.disconnect();int status=new JSONObject(bytes.toString("UTF-8")).optInt("status",response);
                if(status==401)stopSelf();
            }catch(Exception ignored) { /* næste måling forsøger igen */ }
        });
    }
    @Override public void onDestroy() {
        tracking=false;if(manager!=null)try{manager.removeUpdates(this);}catch(Exception ignored){}
        network.shutdownNow();super.onDestroy();
    }
}
