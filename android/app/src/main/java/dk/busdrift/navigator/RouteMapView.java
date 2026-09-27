package dk.busdrift.navigator;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.http.HttpResponseCache;
import android.util.LruCache;
import android.view.View;
import org.json.JSONArray;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Kort med OSM-kortfliser, rute og GPS-position. Henter kun fliser, der vises. */
public final class RouteMapView extends View {
    private static final int SIZE=256;
    private int zoom=16;
    private boolean automatic=true;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ExecutorService downloads=Executors.newFixedThreadPool(3);
    private final LruCache<String,Bitmap> images=new LruCache<String,Bitmap>(48){
        @Override protected int sizeOf(String key,Bitmap value){return 1;}
    };
    private final Set<String> pending=new HashSet<>();
    private final Map<String,Long> failed=new HashMap<>();
    private double lat=55.6761,lon=12.5683;
    private JSONArray geometry;
    private boolean hasPosition;

    public RouteMapView(Context context){
        super(context);
        try {if(HttpResponseCache.getInstalled()==null) HttpResponseCache.install(new File(context.getCacheDir(),"osm-http"),32L*1024*1024);}catch(Exception ignored){}
    }
    public void position(double latitude,double longitude){lat=latitude;lon=longitude;hasPosition=true;if(automatic)fit200m();invalidate();}
    public void zoomIn(){automatic=false;zoom=Math.min(19,zoom+1);invalidate();}
    public void zoomOut(){automatic=false;zoom=Math.max(12,zoom-1);invalidate();}
    public void autoZoom(){automatic=true;fit200m();invalidate();}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);if(automatic)fit200m();}
    private void fit200m(){int pixels=Math.min(getWidth(),getHeight());if(pixels<1)return;
        double ratio=Math.cos(Math.toRadians(lat))*40075017.0*pixels/(256.0*400.0);
        zoom=Math.max(12,Math.min(19,(int)Math.round(Math.log(ratio)/Math.log(2))));
    }
    public void route(JSONArray coordinates){geometry=coordinates;invalidate();}
    public void close(){downloads.shutdownNow();}
    private double x(double longitude){return (longitude+180.0)/360.0*(1<<zoom)*SIZE;}
    private double y(double latitude){double a=Math.toRadians(Math.max(-85,Math.min(85,latitude)));return (1-Math.log(Math.tan(a)+1/Math.cos(a))/Math.PI)/2*(1<<zoom)*SIZE;}
    @Override protected void onDraw(Canvas c){
        c.drawColor(Color.rgb(230,238,233));
        double cx=x(lon),cy=y(lat);int left=(int)Math.floor((cx-getWidth()/2.0)/SIZE),right=(int)Math.floor((cx+getWidth()/2.0)/SIZE);
        int top=(int)Math.floor((cy-getHeight()/2.0)/SIZE),bottom=(int)Math.floor((cy+getHeight()/2.0)/SIZE);
        for(int ty=top;ty<=bottom;ty++)for(int tx=left;tx<=right;tx++){
            if(tx<0||tx>=(1<<zoom)||ty<0||ty>=(1<<zoom))continue;
            String key=zoom+"/"+tx+"/"+ty;Bitmap tile=images.get(key);
            float px=(float)(tx*SIZE-cx+getWidth()/2.0),py=(float)(ty*SIZE-cy+getHeight()/2.0);
            if(tile!=null){paint.setColor(Color.WHITE);c.drawBitmap(tile,px,py,paint);}else loadTile(key);
        }
        if(geometry!=null&&geometry.length()>1){
            android.graphics.Path path=new android.graphics.Path();boolean started=false;
            for(int i=0;i<geometry.length();i++){JSONArray p=geometry.optJSONArray(i);if(p==null)continue;
                float px=(float)(x(p.optDouble(0))-cx+getWidth()/2.0),py=(float)(y(p.optDouble(1))-cy+getHeight()/2.0);
                if(!started){path.moveTo(px,py);started=true;}else path.lineTo(px,py);
            }
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(10);paint.setColor(Color.WHITE);c.drawPath(path,paint);
            paint.setStrokeWidth(6);paint.setColor(Color.rgb(15,108,160));c.drawPath(path,paint);paint.setStyle(Paint.Style.FILL);
            JSONArray end=geometry.optJSONArray(geometry.length()-1);
            if(end!=null){paint.setColor(Color.rgb(183,54,44));c.drawCircle((float)(x(end.optDouble(0))-cx+getWidth()/2.0),(float)(y(end.optDouble(1))-cy+getHeight()/2.0),12,paint);}
        }
        if(hasPosition){paint.setColor(Color.WHITE);c.drawCircle(getWidth()/2f,getHeight()/2f,13,paint);
            paint.setColor(Color.rgb(16,93,176));c.drawCircle(getWidth()/2f,getHeight()/2f,9,paint);}
        paint.setColor(0xEFFFFFFF);c.drawRect(0,getHeight()-34,getWidth(),getHeight(),paint);
        paint.setColor(Color.rgb(24,55,60));paint.setTextSize(15);c.drawText("© OpenStreetMap-bidragydere",12,getHeight()-12,paint);
    }
    private void loadTile(String key){
        synchronized(pending){if(pending.contains(key)||System.currentTimeMillis()-(failed.containsKey(key)?failed.get(key):0)<60000)return;pending.add(key);}
        downloads.execute(()->{
            Bitmap bitmap=null;
            try {
                HttpURLConnection con=(HttpURLConnection)new URL("https://tile.openstreetmap.org/"+key+".png").openConnection();
                con.setRequestProperty("User-Agent","Busdrift-Navigator/4.0 (dk.busdrift.navigator)");con.setConnectTimeout(7000);con.setReadTimeout(7000);con.setUseCaches(true);
                if(con.getResponseCode()==200){try(java.io.InputStream input=con.getInputStream()){bitmap=BitmapFactory.decodeStream(input);}}con.disconnect();
            }catch(Exception ignored){}
            Bitmap result=bitmap;
            post(()->{synchronized(pending){pending.remove(key);if(result==null)failed.put(key,System.currentTimeMillis());}if(result!=null)images.put(key,result);invalidate();});
        });
    }
}
