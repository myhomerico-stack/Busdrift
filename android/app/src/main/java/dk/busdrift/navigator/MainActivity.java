package dk.busdrift.navigator;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Dagens tildelte kørsler: garage -> alle kundestop -> garage -> afslut. */
public final class MainActivity extends Activity {
    private static final String DEFAULT_SERVER="https://minside.hotservice.dk/";
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private JSONObject day;
    private static LocalDate today(){return LocalDate.now(ZoneId.of("Europe/Copenhagen"));}
    private LocalDate selectedDate=today();
    private String notice="";

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.rgb(17,58,65));
        if(token().isEmpty()) loginScreen();else loadDay();
    }
    @Override public void onDestroy() {executor.shutdownNow();super.onDestroy();}
    private String base(){return getPreferences(MODE_PRIVATE).getString("base",DEFAULT_SERVER);}
    private String token(){return getPreferences(MODE_PRIVATE).getString("token","");}
    private void setAuth(String url,String value){getPreferences(MODE_PRIVATE).edit().putString("base",url).putString("token",value).apply();}
    private void message(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private TextView text(String value,int size,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(Color.rgb(18,56,62));t.setPadding(4,8,4,8);if(bold)t.setTypeface(null,1);return t;}
    private void screen(String title){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(22,22,22,40);content.setBackgroundColor(Color.rgb(242,247,245));
        scroll.addView(content);setContentView(scroll);
        TextView logo=text("BUSDRIFT · NAVIGATOR",13,true);logo.setTextColor(Color.rgb(13,103,109));content.addView(logo);
        TextView heading=text(title,27,true);heading.setPadding(4,8,4,16);content.addView(heading);
    }
    private void line(String value){content.addView(text(value,16,false));}
    private void title(String value){TextView t=text(value,19,true);t.setPadding(4,19,4,5);content.addView(t);}
    private void action(String label,Runnable onClick){
        Button button=new Button(this);button.setAllCaps(false);button.setText(label);button.setTextSize(16);button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(17,59,66)));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=9;content.addView(button,params);button.setOnClickListener(v->onClick.run());
    }
    private interface Success {void accept(JSONObject response) throws Exception;}
    private void request(String endpoint,JSONObject body,Success callback){
        final String server=base(),auth=token();
        executor.execute(()->{
            try{
                URL url=new URL(server+"navigator-api.php?action="+endpoint);
                HttpURLConnection conn=(HttpURLConnection)url.openConnection();conn.setConnectTimeout(12000);conn.setReadTimeout(15000);
                conn.setRequestProperty("Accept","application/json");if(!auth.isEmpty())conn.setRequestProperty("Authorization","Bearer "+auth);
                if(body!=null){conn.setRequestMethod("POST");conn.setDoOutput(true);conn.setRequestProperty("Content-Type","application/json; charset=utf-8");
                    try(OutputStream output=conn.getOutputStream()){output.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
                int status=conn.getResponseCode();InputStream input=status>=400?conn.getErrorStream():conn.getInputStream();
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();if(input!=null)try(InputStream stream=input){byte[] buffer=new byte[4096];int read;
                    while((read=stream.read(buffer))!=-1){bytes.write(buffer,0,read);if(bytes.size()>1500000)throw new Exception("Svaret er for stort.");}}
                conn.disconnect();JSONObject result=new JSONObject(bytes.toString("UTF-8"));
                if(status>=400||result.has("error")){int errorCode=result.optInt("status",status);String error=result.optString("error","Serverfejl");
                    main.post(()->{if(errorCode==401&&!auth.isEmpty()){setAuth(server,"");stopGps();loginScreen();}message(error);});return;}
                main.post(()->{try{callback.accept(result);}catch(Exception e){message(e.getMessage());}});
            }catch(Exception e){main.post(()->message("Forbindelsen til Busdrift virker ikke: "+e.getMessage()));}
        });
    }
    private void loginScreen(){
        screen("Chaufførlogin");if(!notice.isEmpty()){line(notice);notice="";}
        line("Log ind med chaufførnummer og PIN fra Busdrift.");
        EditText url=new EditText(this);url.setSingleLine(true);url.setHint("Serveradresse (HTTPS)");url.setText(base());content.addView(url);
        EditText number=new EditText(this);number.setSingleLine(true);number.setHint("Chaufførnummer");content.addView(number);
        EditText pin=new EditText(this);pin.setSingleLine(true);pin.setHint("PIN");pin.setInputType(129);content.addView(pin);
        action("Log ind",()->{
            String typedServer=url.getText().toString().trim();final String server=typedServer.endsWith("/")?typedServer:typedServer+"/";
            Uri parsed=Uri.parse(server);
            if(!"https".equalsIgnoreCase(parsed.getScheme())||parsed.getHost()==null||parsed.getUserInfo()!=null||parsed.getQuery()!=null||parsed.getFragment()!=null){message("Indtast en HTTPS-adresse til Busdrift.");return;}
            if(number.getText().toString().trim().isEmpty()||pin.getText().length()==0){message("Udfyld chaufførnummer og PIN.");return;}
            setAuth(server,"");
            try{JSONObject body=new JSONObject();body.put("number",number.getText().toString().trim());body.put("pin",pin.getText().toString());
                request("login",body,result->{pin.setText("");setAuth(server,result.getString("token"));selectedDate=today();loadDay();});}
            catch(Exception e){message(e.getMessage());}
        });
        line("Serveren læser MySQL via data/settings.php. PIN og databaseadgang gemmes ikke i appen.");
    }
    private void loadDay(){screen("Dagens kørsler");line("Henter rute …");
        request("day&date="+selectedDate.format(DateTimeFormatter.ISO_LOCAL_DATE),null,result->{day=result;showDay();});}
    private JSONObject nextPoint(){
        JSONArray list=day==null?null:day.optJSONArray("waypoints");if(list==null)return null;
        for(int i=0;i<list.length();i++){JSONObject point=list.optJSONObject(i);if(point!=null&&!point.optBoolean("done"))return point;}
        return null;
    }
    private void showDay(){
        if(day==null)return;JSONObject driver=day.optJSONObject("driver");String driverName=driver==null?"Chauffør":driver.optString("number")+" · "+driver.optString("name");
        screen("Dagens kørsler");line(driverName);title(selectedDate.format(DateTimeFormatter.ofPattern("dd-MM-yyyy")));
        action("← Forrige dag",()->{selectedDate=selectedDate.minusDays(1);loadDay();});
        action("Næste dag →",()->{selectedDate=selectedDate.plusDays(1);loadDay();});
        action("I dag · opdater",()->{selectedDate=today();loadDay();});
        if(day.optBoolean("completed")){title("Dagen er afsluttet");line("Alle ture og returen til garagen er registreret.");manualLogout();return;}
        JSONArray trips=day.optJSONArray("tours");
        if(trips==null||trips.length()==0){line("Der er ingen tildelte ture denne dag.");manualLogout();return;}
        title("Tildelte ture");for(int i=0;i<trips.length();i++){
            JSONObject t=trips.optJSONObject(i);if(t==null)continue;
            line(t.optString("startTime")+"–"+t.optString("endTime")+" · "+t.optString("customer")+" · "+t.optString("bus"));
            if(!t.optString("description").trim().isEmpty())line("Besked: "+t.optString("description"));
        }
        JSONArray route=day.optJSONArray("waypoints");JSONObject next=nextPoint();boolean today=selectedDate.equals(today());
        if(next!=null){title("Næste stop");line(next.optString("title")+"\n"+next.optString("address"));
            if(next.optInt("pause")>0)line("Pause ved stop: "+next.optInt("pause")+" min.");
            if(today){
                if(!next.optString("type").equals("garage_start"))action("Naviger til næste stop",()->navigate(next.optString("address")));
                String button=next.optString("type").equals("garage_start")?"Start kørslen fra garagen":
                    next.optString("type").equals("garage_return")?"Jeg er tilbage ved garagen":"Jeg er ankommet til stoppet";
                action(button,()->completePoint(next));
            }
        }else if(today){title("Alle stop er kørt");action("Afslut dagen og log ud",this::finishDay);}
        title("Hele ruten");if(route!=null)for(int i=0;i<route.length();i++){
            JSONObject p=route.optJSONObject(i);if(p==null)continue;
            line((p.optBoolean("done")?"✓  ":"○  ")+(i+1)+". "+p.optString("title")+" · "+p.optString("address"));
        }
        if(today)line("Navigationen åbner din kortapp til ét stop ad gangen. Vælg busprofil i kortappen, hvis din bus kræver det.");
        manualLogout();
    }
    private void completePoint(JSONObject point){
        try{JSONObject body=new JSONObject();body.put("date",selectedDate.toString());body.put("key",point.getString("key"));
            request("waypoint",body,result->{
                request("day&date="+selectedDate,null,newDay->{day=newDay;showDay();JSONObject next=nextPoint();
                    if(next!=null&&!next.optString("type").equals("garage_start")){startGps(next.optInt("tourId"));navigate(next.optString("address"));}
                    if(next==null)stopGps();
                });
            });
        }catch(Exception e){message(e.getMessage());}
    }
    private void navigate(String address){
        if(address.trim().isEmpty()){message("Stoppet mangler adresse.");return;}
        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("google.navigation:q="+Uri.encode(address))));}
        catch(Exception e){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.google.com/maps/dir/?api=1&destination="+Uri.encode(address))));}
            catch(Exception ignored){message("Ingen navigationsapp er installeret.");}}
    }
    private void startGps(int tour){
        if(tour<1)return;
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},23);return;
        }
        Intent service=new Intent(this,GpsService.class);service.putExtra("base",base());service.putExtra("token",token());service.putExtra("tourId",tour);
        try{startForegroundService(service);}catch(Exception e){message("GPS kunne ikke startes: "+e.getMessage());}
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){super.onRequestPermissionsResult(code,permissions,grants);
        if(code==23){boolean allowed=false;for(int grant:grants)if(grant==PackageManager.PERMISSION_GRANTED)allowed=true;
            if(allowed&&day!=null){JSONObject next=nextPoint();int id=next!=null?next.optInt("tourId"):day.optJSONArray("tours").optJSONObject(0).optInt("id");startGps(id);}
            else message("Du kan stadig navigere, men GPS deles ikke uden placeringstilladelse.");
        }
    }
    private void stopGps(){stopService(new Intent(this,GpsService.class));}
    private void finishDay(){try{JSONObject body=new JSONObject();body.put("date",selectedDate.toString());
        request("finish",body,result->{stopGps();setAuth(base(),"");day=null;notice="Dagen er afsluttet. Du er logget ud.";loginScreen();});
    }catch(Exception e){message(e.getMessage());}}
    private void manualLogout(){action("Log ud (uden at afslutte dagen)",()->request("logout",new JSONObject(),result->{stopGps();setAuth(base(),"");day=null;notice="Du er logget ud. Dagen er ikke afsluttet.";loginScreen();}));}
}
