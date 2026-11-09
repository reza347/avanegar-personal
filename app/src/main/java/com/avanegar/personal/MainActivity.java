package com.avanegar.personal;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.view.*;
import android.widget.*;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import okhttp3.*;
import okio.BufferedSink;

public class MainActivity extends Activity {
  private static final int BG=0xff090c1c, CARD=0xff151a34, PURPLE=0xff8956f5, MUTED=0xffb2b5d0, WHITE=0xfff7f6ff;
  private final Handler ui = new Handler(Looper.getMainLooper());
  private final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(25, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build();
  private LinearLayout column;
  private EditText editor;
  private TextView status;
  private Button recordButton;
  private WebSocket socket;
  private AudioRecord microphone;
  private Thread audioThread;
  private volatile boolean recording=false, stopping=false;
  private String done="", partial="", screen="main";
  private static final int AUDIO_PERMISSION=33, PICK_AUDIO=34, SAVE_TEXT=35;
  private final String keyAlias="avanegar_personal_api_key_v1";

  @Override public void onCreate(Bundle state){
    super.onCreate(state);
    getWindow().setStatusBarColor(BG);
    getWindow().setNavigationBarColor(BG);
    getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    showMain();
  }
  private int dp(int x){return Math.round(getResources().getDisplayMetrics().density*x);}
  private GradientDrawable shape(int color,int radius){
    GradientDrawable d=new GradientDrawable();
    d.setColor(color); d.setCornerRadius(dp(radius)); return d;
  }
  private TextView label(String text,int size,int color,boolean bold){
    TextView v=new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(color);
    v.setGravity(Gravity.RIGHT); v.setTextDirection(View.TEXT_DIRECTION_RTL);
    v.setPadding(0,dp(5),0,dp(5)); if(bold)v.setTypeface(null,Typeface.BOLD); return v;
  }
  private void add(View v,int top){
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.topMargin=dp(top);
    column.addView(v,p);
  }
  private Button button(String title,boolean primary,Runnable action){
    Button b=new Button(this); b.setText(title); b.setTextSize(15); b.setAllCaps(false);
    b.setTextColor(WHITE); b.setBackground(shape(primary?PURPLE:CARD,14));
    b.setPadding(dp(14),dp(10),dp(14),dp(10));
    b.setOnClickListener(v->action.run()); return b;
  }
  private void base(String title,String subtitle){
    screen=title.equals("آوانگار")?"main":"sub";
    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
    column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);
    column.setPadding(dp(20),dp(25),dp(20),dp(48));column.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    scroll.addView(column);setContentView(scroll);
    add(label(title,30,WHITE,true),0);
    add(label(subtitle,14,MUTED,false),2);
  }
  private void toast(String msg){ui.post(()->Toast.makeText(this,msg,Toast.LENGTH_LONG).show());}
  private void setStatus(String text){ui.post(()->{if(status!=null)status.setText(text);});}
  private String apiKey(){
    try{
      SharedPreferences p=getSharedPreferences("secure",MODE_PRIVATE);
      String data=p.getString("cipher",null), iv=p.getString("iv",null);
      if(data==null || iv==null)return "";
      KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
      SecretKey key=(SecretKey)ks.getKey(keyAlias,null);
      if(key==null)return "";
      Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));
      return new String(c.doFinal(Base64.decode(data,Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }catch(Exception e){return "";}
  }
  private void storeKey(String value)throws Exception{
    KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
    if(!ks.containsAlias(keyAlias)){
      KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
      kg.init(new KeyGenParameterSpec.Builder(keyAlias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
      kg.generateKey();
    }
    SecretKey k=(SecretKey)ks.getKey(keyAlias,null);
    Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,k);
    byte[] encrypted=c.doFinal(value.getBytes(StandardCharsets.UTF_8));
    getSharedPreferences("secure",MODE_PRIVATE).edit()
      .putString("cipher",Base64.encodeToString(encrypted,Base64.NO_WRAP))
      .putString("iv",Base64.encodeToString(c.getIV(),Base64.NO_WRAP)).apply();
  }
  private void showSettings(){
    if(recording){toast("ابتدا ضبط را متوقف کنید.");return;}
    captureEditor();
    base("تنظیمات","مدیریت اتصال به OpenAI فقط برای استفاده شخصی");
    add(label("کلید OpenAI API",20,WHITE,true),25);
    EditText keyBox=new EditText(this);keyBox.setSingleLine(true);keyBox.setTextColor(WHITE);
    keyBox.setHintTextColor(MUTED);keyBox.setHint("sk-...");
    keyBox.setText(apiKey());keyBox.setTextDirection(View.TEXT_DIRECTION_LTR);
    keyBox.setBackground(shape(CARD,12));keyBox.setPadding(dp(14),dp(16),dp(14),dp(16));
    keyBox.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
    keyBox.setTransformationMethod(PasswordTransformationMethod.getInstance());
    add(keyBox,14);
    add(button("🔑  ذخیره کلید",true,()->{
      String value=keyBox.getText().toString().trim();
      if(value.length()<10){toast("کلید واردشده معتبر به نظر نمی‌رسد.");return;}
      try{storeKey(value);toast("کلید با رمزگذاری روی گوشی ذخیره شد.");}
      catch(Exception e){toast("خطا در ذخیره امن کلید");}
    }),15);
    add(button("✅  آزمایش اتصال",false,this::testKey),12);
    add(button("🗑  حذف کلید",false,()->{
      getSharedPreferences("secure",MODE_PRIVATE).edit().clear().apply();
      keyBox.setText("");toast("کلید حذف شد.");
    }),12);
    add(label("کلید API فقط در حافظه خصوصی گوشی رمزگذاری می‌شود. گفتار برای رونویسی از طریق اینترنت به OpenAI ارسال می‌شود. هزینه‌ها از اعتبار API شما کسر می‌شود.",14,MUTED,false),28);
    add(button("بازگشت",false,this::showMain),20);
  }
  private void testKey(){
    String key=apiKey();if(key.isEmpty()){toast("ابتدا کلید را ذخیره کنید.");return;}
    setStatus("در حال بررسی کلید...");
    Request r=new Request.Builder().url("https://api.openai.com/v1/models")
      .header("Authorization","Bearer "+key).get().build();
    http.newCall(r).enqueue(new Callback(){
      @Override public void onFailure(Call c,IOException e){toast("اتصال اینترنت یا OpenAI برقرار نشد.");}
      @Override public void onResponse(Call c,Response response){
        try(Response body=response){
          if(response.isSuccessful())toast("کلید معتبر است. دسترسی به مدل‌ها هنگام رونویسی بررسی می‌شود.");
          else toast("خطای اتصال: "+response.code()+"؛ کلید یا اعتبار حساب را بررسی کنید.");
        }
      }
    });
  }
  private void showMain(){
    base("آوانگار  🎙","تبدیل گفتار فارسی به نوشتار با هوش مصنوعی");
    status=label("آماده برای ضبط فارسی",15,0xffb3b6ea,true);add(status,25);
    recordButton=button(recording?"⏹  پایان ضبط":"🎙  شروع رونویسی زنده",true,()->{
      if(recording)stopRecording();else startRecording();
    });
    add(recordButton,20);
    add(button("🎵  تبدیل فایل صوتی",false,this::pickFile),12);
    add(button("⚙  تنظیمات کلید OpenAI",false,this::showSettings),12);
    add(button("📚  تاریخچه متن‌ها",false,this::showHistory),12);
    add(label("متن فارسی",20,WHITE,true),25);
    editor=new EditText(this);editor.setTextSize(17);editor.setTextColor(WHITE);
    editor.setHintTextColor(MUTED);editor.setHint("بعد از صحبت کردن، متن در اینجا نمایش داده می‌شود…");
    editor.setGravity(Gravity.TOP|Gravity.RIGHT);editor.setTextDirection(View.TEXT_DIRECTION_RTL);
    editor.setMinLines(8);editor.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    editor.setBackground(shape(CARD,16));editor.setPadding(dp(16),dp(16),dp(16),dp(16));editor.setText(done+partial);
    add(editor,12);
    add(button("💾  ذخیره متن در تاریخچه",false,this::saveHistory),18);
    add(button("📋  کپی متن",false,()->{
      ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
      cm.setPrimaryClip(ClipData.newPlainText("متن فارسی",editor.getText().toString()));toast("متن کپی شد.");
    }),10);
    add(button("↗  اشتراک‌گذاری متن",false,()->{
      Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");
      i.putExtra(Intent.EXTRA_TEXT,editor.getText().toString());startActivity(Intent.createChooser(i,"اشتراک متن"));
    }),10);
    add(button("📄  خروجی TXT",false,()->{
      Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
      i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("text/plain");
      i.putExtra(Intent.EXTRA_TITLE,"avanegar-persian.txt");
      startActivityForResult(i,SAVE_TEXT);
    }),10);
    add(label("مدل زنده: gpt-live-transcribe | فایل: gpt-transcribe\nاین برنامه صرفاً فارسی است و برای استفاده شخصی طراحی شده است.",12,MUTED,false),25);
  }
  private void captureEditor(){
    if(editor!=null && screen.equals("main") && !recording){done=editor.getText().toString();partial="";}
  }
  private void saveHistory(){
    if(editor==null)return;
    String txt=editor.getText().toString().trim();
    if(txt.isEmpty()){toast("متنی برای ذخیره وجود ندارد.");return;}
    try{
      SharedPreferences p=getSharedPreferences("history",MODE_PRIVATE);
      JSONArray a=new JSONArray(p.getString("items","[]"));
      JSONObject item=new JSONObject();item.put("time",System.currentTimeMillis());item.put("text",txt);
      JSONArray b=new JSONArray();b.put(item);
      for(int i=0;i<a.length() && i<49;i++)b.put(a.getJSONObject(i));
      p.edit().putString("items",b.toString()).apply();toast("متن ذخیره شد.");
    }catch(Exception e){toast("ذخیره انجام نشد.");}
  }
  private void showHistory(){
    if(recording){toast("ابتدا ضبط را متوقف کنید.");return;}
    captureEditor();
    base("تاریخچه","متن‌ها فقط در حافظه خصوصی این گوشی نگهداری می‌شوند");
    try{
      JSONArray a=new JSONArray(getSharedPreferences("history",MODE_PRIVATE).getString("items","[]"));
      if(a.length()==0)add(label("هنوز متنی ذخیره نشده است.",16,MUTED,false),25);
      for(int i=0;i<a.length();i++){
        JSONObject item=a.getJSONObject(i);String txt=item.optString("text");
        String title=txt.length()>70?txt.substring(0,70)+"…":txt;
        add(button("📝 "+title,false,()->{done=txt;partial="";showMain();}),12);
      }
    }catch(Exception e){toast("خطا در نمایش تاریخچه");}
    add(button("🗑  پاک کردن تاریخچه",false,()->{
      new android.app.AlertDialog.Builder(this).setMessage("همه متن‌های ذخیره‌شده حذف شوند؟")
        .setPositiveButton("حذف",(d,w)->{getSharedPreferences("history",MODE_PRIVATE).edit().clear().apply();showHistory();})
        .setNegativeButton("انصراف",null).show();
    }),20);
    add(button("بازگشت",true,this::showMain),15);
  }
  private void pickFile(){
    if(recording){toast("ابتدا ضبط را متوقف کنید.");return;}
    if(apiKey().isEmpty()){toast("ابتدا کلید OpenAI را در تنظیمات وارد کنید.");return;}
    Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
    i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("audio/*");
    startActivityForResult(i,PICK_AUDIO);
  }
  @Override protected void onActivityResult(int request,int result,Intent data){
    super.onActivityResult(request,result,data);
    if(result!=RESULT_OK || data==null || data.getData()==null)return;
    Uri uri=data.getData();
    if(request==PICK_AUDIO)transcribeFile(uri);
    if(request==SAVE_TEXT){
      String txt=editor==null?done:editor.getText().toString();
      try(OutputStream out=getContentResolver().openOutputStream(uri)){
        if(out==null)throw new IOException("no stream");
        out.write(txt.getBytes(StandardCharsets.UTF_8));toast("فایل متنی ذخیره شد.");
      }catch(Exception e){toast("ذخیره فایل انجام نشد.");}
    }
  }
  private String audioName(Uri uri){
    String name="";
    try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
      if(c!=null && c.moveToFirst())name=c.getString(0);
    }catch(Exception ignored){}
    String ext="";
    if(name!=null && name.lastIndexOf('.')>=0)ext=name.substring(name.lastIndexOf('.')+1).toLowerCase(java.util.Locale.ROOT);
    if(!java.util.Arrays.asList("mp3","mp4","mpeg","mpga","m4a","wav","webm").contains(ext)){
      String mime=getContentResolver().getType(uri);
      if("audio/mpeg".equals(mime) || "audio/mp3".equals(mime))ext="mp3";
      else if("audio/wav".equals(mime) || "audio/x-wav".equals(mime))ext="wav";
      else if("audio/webm".equals(mime))ext="webm";
      else ext="m4a";
    }
    return "audio."+ext;
  }
  private void transcribeFile(Uri uri){
    long size=-1;
    try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.SIZE},null,null,null)){
      if(c!=null && c.moveToFirst() && !c.isNull(0))size=c.getLong(0);
    }catch(Exception ignored){}
    if(size>24L*1024*1024){toast("حجم فایل باید کمتر از ۲۴ مگابایت باشد.");return;}
    final long length=size;
    setStatus("در حال ارسال فایل و تبدیل به متن...");
    RequestBody stream=new RequestBody(){
      @Override public MediaType contentType(){return MediaType.parse("application/octet-stream");}
      @Override public long contentLength(){return length;}
      @Override public void writeTo(BufferedSink sink)throws IOException{
        try(InputStream in=getContentResolver().openInputStream(uri)){
          if(in==null)throw new IOException("Cannot open audio");
          byte[] buf=new byte[8192];int n;long count=0;
          while((n=in.read(buf))!=-1){
            count+=n;if(count>24L*1024*1024)throw new IOException("فایل بیش از حد بزرگ است");
            sink.write(buf,0,n);
          }
        }
      }
    };
    RequestBody body=new MultipartBody.Builder().setType(MultipartBody.FORM)
      .addFormDataPart("model","gpt-transcribe")
      .addFormDataPart("languages[]","fa")
      .addFormDataPart("file",audioName(uri),stream).build();
    Request req=new Request.Builder().url("https://api.openai.com/v1/audio/transcriptions")
      .header("Authorization","Bearer "+apiKey()).post(body).build();
    OkHttpClient upload=http.newBuilder().callTimeout(0,TimeUnit.MILLISECONDS).readTimeout(180,TimeUnit.SECONDS).build();
    upload.newCall(req).enqueue(new Callback(){
      @Override public void onFailure(Call c,IOException e){setStatus("ارسال فایل ناموفق بود.");toast("خطای شبکه یا فایل: "+e.getMessage());}
      @Override public void onResponse(Call c,Response r){
        try(Response resp=r){
          String text=resp.body()==null?"":resp.body().string();
          if(!resp.isSuccessful()){setStatus("خطا در رونویسی فایل: "+resp.code());return;}
          String transcript=new JSONObject(text).optString("text","");
          ui.post(()->{
            done=transcript;partial="";
            if(editor!=null)editor.setText(done);
            setStatus("تبدیل فایل کامل شد.");
          });
        }catch(Exception e){setStatus("پاسخ فایل قابل خواندن نبود.");}
      }
    });
  }
  private void startRecording(){
    if(apiKey().isEmpty()){toast("ابتدا از تنظیمات، کلید OpenAI را وارد کنید.");return;}
    if(Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
      requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},AUDIO_PERMISSION);return;
    }
    recording=true;stopping=false;done=editor==null?"":editor.getText().toString();partial="";
    recordButton.setText("⏹  پایان ضبط");setStatus("در حال اتصال به OpenAI...");
    Request req=new Request.Builder().url("wss://api.openai.com/v1/realtime?intent=transcription")
      .header("Authorization","Bearer "+apiKey()).build();
    socket=http.newWebSocket(req,new WebSocketListener(){
      @Override public void onOpen(WebSocket ws,Response response){
        String config="{\"type\":\"session.update\",\"session\":{\"type\":\"transcription\",\"audio\":{\"input\":{\"format\":{\"type\":\"audio/pcm\",\"rate\":24000},\"transcription\":{\"model\":\"gpt-live-transcribe\",\"languages\":[\"fa\"],\"delay\":\"low\"},\"turn_detection\":null}}}}";
        ws.send(config);setStatus("در انتظار آماده شدن مدل...");
      }
      @Override public void onMessage(WebSocket ws,String text){
        try{
          JSONObject event=new JSONObject(text);String type=event.optString("type");
          if(type.equals("session.updated")){
            setStatus("در حال شنیدن گفتار فارسی...");beginMicrophone(ws);
          }else if(type.equals("conversation.item.input_audio_transcription.delta")){
            partial+=event.optString("delta");refreshEditor();
          }else if(type.equals("conversation.item.input_audio_transcription.completed")){
            String transcript=event.optString("transcript","").trim();
            if(!transcript.isEmpty())done+=(done.isEmpty()?"":"\n")+transcript;
            partial="";refreshEditor();
          }else if(type.equals("error")){
            JSONObject error=event.optJSONObject("error");
            setStatus("خطای OpenAI: "+(error==null?"نامشخص":error.optString("message","نامشخص")));
            ui.post(()->stopRecording());
          }
        }catch(Exception ignored){}
      }
      @Override public void onFailure(WebSocket ws,Throwable t,Response response){
        ui.post(()->{if(recording)stopRecording();setStatus("اتصال زنده قطع شد. اینترنت و دسترسی مدل را بررسی کنید.");});
      }
      @Override public void onClosed(WebSocket ws,int code,String reason){
        if(!stopping)setStatus("اتصال بسته شد.");
      }
    });
  }
  private void refreshEditor(){
    ui.post(()->{
      if(editor!=null && screen.equals("main")){
        editor.setText(done+partial);
        editor.setSelection(editor.length());
      }
    });
  }
  private void beginMicrophone(WebSocket ws){
    if(!recording || audioThread!=null)return;
    audioThread=new Thread(()->{
      int rate=24000;int min=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
      if(min<=0){setStatus("میکروفون از نرخ ۲۴ کیلوهرتز پشتیبانی نمی‌کند.");ui.post(this::stopRecording);return;}
      byte[] block=new byte[4800]; // 100ms of mono PCM16 at 24kHz
      boolean voiced=false;long lastVoice=0, firstVoice=0;long lastCommit=System.currentTimeMillis();
      try{
        microphone=new AudioRecord(MediaRecorder.AudioSource.MIC,rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,19200));
        if(microphone.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("Microphone unavailable");
        microphone.startRecording();
        while(recording){
          int n=microphone.read(block,0,block.length);
          if(n<=0)continue;
          ws.send(new JSONObject().put("type","input_audio_buffer.append")
            .put("audio",Base64.encodeToString(block,0,n,Base64.NO_WRAP)).toString());
          long now=System.currentTimeMillis();
          long total=0;int samples=n/2;
          for(int i=0;i+1<n;i+=2){int s=(short)((block[i]&255)|(block[i+1]<<8));total+=Math.abs(s);}
          double level=samples>0?(double)total/samples:0;
          if(level>700){lastVoice=now;if(!voiced)firstVoice=now;voiced=true;}
          if(voiced && ((now-lastVoice>850 && now-firstVoice>450)||(now-lastCommit>15000))){
            ws.send("{\"type\":\"input_audio_buffer.commit\"}");
            lastCommit=now;voiced=false;
          }
        }
        ws.send("{\"type\":\"input_audio_buffer.commit\"}");
      }catch(Exception e){if(recording)setStatus("خطای ضبط صدا: "+e.getMessage());}
      finally{
        try{if(microphone!=null){microphone.stop();microphone.release();}}catch(Exception ignored){}
        microphone=null;audioThread=null;
      }
    },"avanegar-microphone");
    audioThread.start();
  }
  private void stopRecording(){
    if(!recording && !stopping)return;
    recording=false;stopping=true;
    if(recordButton!=null)recordButton.setText("🎙  شروع رونویسی زنده");
    setStatus("در حال تکمیل آخرین بخش متن...");
    WebSocket ws=socket;
    ui.postDelayed(()->{
      if(ws!=null)ws.close(1000,"finished");
      if(socket==ws)socket=null;
      stopping=false;setStatus("ضبط پایان یافت. متن را ذخیره کنید.");
    },2400);
  }
  @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
    super.onRequestPermissionsResult(request,permissions,results);
    if(request==AUDIO_PERMISSION){
      if(results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED)startRecording();
      else toast("برای رونویسی زنده، اجازه میکروفون لازم است.");
    }
  }
  @Override public void onBackPressed(){
    if(recording){stopRecording();return;}
    if(screen.equals("sub"))showMain();else super.onBackPressed();
  }
  @Override protected void onDestroy(){
    recording=false;
    if(socket!=null)socket.close(1000,"closed");
    super.onDestroy();
  }
}
