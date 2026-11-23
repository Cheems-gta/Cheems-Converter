package win.cheems.converter;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends Activity {
    private static final int PICK = 391;
    private static final int BLUE = Color.rgb(40, 96, 223), INK = Color.rgb(27, 39, 61);
    private static final String[] FORMATS = {"MP4","MKV","MOV","AVI","WebM","FLV","WMV","3GP","MPEG","TS","GIF","MP3","M4A","WAV","FLAC","OGG","Opus","AAC"};
    private static final String[] QUALITIES = {"标准 · 平衡画质","高清 · 画质优先","压缩 · 体积优先"};
    private final ArrayList<Uri> selections = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<FFmpegSession> session = new AtomicReference<>();
    private Spinner formatSpinner, qualitySpinner;
    private Button chooseButton, convertButton, cancelButton;
    private ProgressBar bar;
    private TextView filesText, statusText, percentText, resultText;
    private boolean running = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(246,248,253));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        screen();
    }
    private int dp(int a) { return (int)(a*getResources().getDisplayMetrics().density+0.5f); }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radius)); return g;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private LinearLayout.LayoutParams margin(int height, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,height==-2?-2:dp(height));
        p.topMargin = dp(top); return p;
    }
    private Button button(String caption, int color, int foreground) {
        Button b = new Button(this); b.setText(caption); b.setTextSize(15); b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD); b.setTextColor(foreground);
        b.setBackground(background(color,14)); return b;
    }
    private Spinner picker(String[] items) {
        Spinner s = new Spinner(this); ArrayAdapter<String> a = new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); s.setAdapter(a);
        s.setBackground(background(Color.rgb(239,244,253),12)); s.setPadding(dp(12),0,dp(12),0);
        return s;
    }
    private void screen() {
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(Color.rgb(247,249,254));
        LinearLayout outer = new LinearLayout(this); outer.setPadding(dp(20),dp(27),dp(20),dp(25));
        outer.setOrientation(LinearLayout.VERTICAL); scroll.addView(outer);
        outer.addView(text("CHEEMS LAB / MEDIA TOOLS",12,BLUE,true));
        outer.addView(text("Cheems Converter",29,INK,true), margin(-2,9));
        outer.addView(text("本地视频转换 · 音频提取 · 批量处理",14,Color.GRAY,false),margin(-2,7));
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(background(Color.WHITE,22)); panel.setPadding(dp(18),dp(21),dp(18),dp(21));
        outer.addView(panel,margin(-2,23));
        panel.addView(text("01  选择文件（最多20个）",17,INK,true));
        filesText = text("尚未选择视频或音频",13,Color.GRAY,false); filesText.setMaxLines(6);
        panel.addView(filesText,margin(-2,11));
        chooseButton=button("＋  选择文件",BLUE,Color.WHITE); panel.addView(chooseButton,margin(52,13));
        chooseButton.setOnClickListener(v->pickFiles());
        panel.addView(text("02  目标格式",17,INK,true),margin(-2,23));
        formatSpinner=picker(FORMATS); panel.addView(formatSpinner,margin(52,10));
        panel.addView(text("03  画质和大小",17,INK,true),margin(-2,23));
        qualitySpinner=picker(QUALITIES); panel.addView(qualitySpinner,margin(52,10));
        convertButton=button("▶  开始转换",BLUE,Color.WHITE); panel.addView(convertButton,margin(55,23));
        convertButton.setOnClickListener(v->begin());
        cancelButton=button("取消任务",Color.rgb(234,238,248),INK); panel.addView(cancelButton,margin(46,10));
        cancelButton.setVisibility(View.GONE); cancelButton.setOnClickListener(v->{
            cancelled.set(true); FFmpegSession s=session.get();
            if(s!=null) FFmpegKit.cancel(s.getSessionId()); status("正在取消…");
        });
        LinearLayout info = new LinearLayout(this); info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(17),dp(18),dp(17),dp(18)); info.setBackground(background(Color.rgb(232,240,255),16));
        outer.addView(info,margin(-2,18));
        statusText=text("等待任务",16,INK,true); info.addView(statusText);
        bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000); bar.setProgressTintList(android.content.res.ColorStateList.valueOf(BLUE));
        info.addView(bar,margin(8,13));
        percentText=text("0%",13,BLUE,true); info.addView(percentText,margin(-2,8));
        resultText=text("转换文件保存在 Movies、Music 或 Pictures 的 CheemsConverter 文件夹。",12,Color.GRAY,false);
        info.addView(resultText,margin(-2,8));
        TextView foot=text("仅在手机本地处理 · 不上传文件\n转换大型视频请保持程序打开",12,Color.GRAY,false);
        foot.setGravity(Gravity.CENTER); outer.addView(foot,margin(-2,17)); setContentView(scroll);
    }
    private void pickFiles() {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        startActivityForResult(i,PICK);
    }
    @Override protected void onActivityResult(int req,int res,Intent data) {
        super.onActivityResult(req,res,data);
        if(req!=PICK||res!=RESULT_OK||data==null)return;
        selections.clear();
        if(data.getClipData()!=null) {
            for(int i=0;i<Math.min(20,data.getClipData().getItemCount());i++)
                selections.add(data.getClipData().getItemAt(i).getUri());
        } else if(data.getData()!=null) selections.add(data.getData());
        StringBuilder b=new StringBuilder("已选择 "+selections.size()+" 个文件\n");
        for(int i=0;i<Math.min(5,selections.size());i++)b.append("• ").append(filename(selections.get(i))).append("\n");
        if(selections.size()>5)b.append("…还有其他文件");
        filesText.setText(b.toString().trim()); status("文件就绪");
    }
    private String filename(Uri uri) {
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
            if(c!=null&&c.moveToFirst())return c.getString(0);
        } catch(Exception ignored) {}
        return "未命名文件";
    }
    private long lengthMs(Uri uri) {
        MediaMetadataRetriever m=new MediaMetadataRetriever();
        try {m.setDataSource(this,uri); String s=m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
             return s==null?0:Long.parseLong(s);
        } catch(Exception ignored) {return 0;}
        finally {try{m.release();}catch(Exception ignored){}}
    }
    private void begin() {
        if(running||selections.isEmpty()){if(selections.isEmpty())status("请先选择文件");return;}
        running=true; cancelled.set(false);
        chooseButton.setEnabled(false); convertButton.setEnabled(false);
        formatSpinner.setEnabled(false); qualitySpinner.setEnabled(false); cancelButton.setVisibility(View.VISIBLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        bar.setProgress(0); percentText.setText("0%");
        resultText.setText("转换中请保持应用打开");
        ArrayList<Uri> jobs=new ArrayList<>(selections);
        String f=FORMATS[formatSpinner.getSelectedItemPosition()].toLowerCase(Locale.ROOT);
        int q=qualitySpinner.getSelectedItemPosition();
        executor.execute(()->convertAll(jobs,f,q));
    }
    private void convertAll(ArrayList<Uri> jobs,String format,int quality) {
        int successful=0; StringBuilder report=new StringBuilder();
        for(int i=0;i<jobs.size()&&!cancelled.get();i++) {
            Uri uri=jobs.get(i); String name=filename(uri);
            File input=null,output=null;
            try {
                String ext=".bin"; int dot=name.lastIndexOf('.');
                if(dot>=0&&name.length()-dot<=12) {
                    String candidate=name.substring(dot).toLowerCase(Locale.ROOT);
                    if(candidate.matches("\\.[a-z0-9]{1,10}"))ext=candidate;
                }
                input=new File(getCacheDir(),"in_"+UUID.randomUUID()+ext);
                output=new File(getCacheDir(),"out_"+UUID.randomUUID()+"."+extension(format));
                status("正在读取 "+(i+1)+"/"+jobs.size()+" · "+name);
                try(InputStream stream=getContentResolver().openInputStream(uri);OutputStream target=new FileOutputStream(input)){
                    if(stream==null)throw new IllegalStateException("无法打开文件");
                    byte[] buf=new byte[128*1024]; int n;
                    while((n=stream.read(buf))!=-1) {
                        if(cancelled.get())break;
                        target.write(buf,0,n);
                    }
                }
                if(cancelled.get())break;
                status("正在转换 "+(i+1)+"/"+jobs.size()+" · "+name);
                long dur=lengthMs(uri);
                final int idx=i;
                CountDownLatch latch=new CountDownLatch(1);
                AtomicReference<FFmpegSession> completed=new AtomicReference<>();
                FFmpegSession runningSession=FFmpegKit.executeAsync(command(input,output,format,quality),s->{
                    completed.set(s); latch.countDown();
                },null,statistics->{
                    int pct=dur>0?(int)Math.min(99,100L*statistics.getTime()/dur):0;
                    runOnUiThread(()->{
                        bar.setProgress((int)(1000.0*(idx+pct/100.0)/jobs.size()));
                        percentText.setText(dur>0?pct+"% · 第"+(idx+1)+"个文件":"正在处理第"+(idx+1)+"个文件");
                    });
                });
                session.set(runningSession);
                if(cancelled.get())FFmpegKit.cancel(runningSession.getSessionId());
                latch.await();
                session.set(null);
                if(cancelled.get())break;
                FFmpegSession result=completed.get();
                if(result!=null&&ReturnCode.isSuccess(result.getReturnCode())&&output.isFile()&&output.length()>0) {
                    String base=name.replaceAll("(?i)\\.[^.]+$","").replaceAll("[\\r\\n/\\\\]","_");
                    if(base.isEmpty())base="media";
                    String newName=base+"_cheems_"+System.currentTimeMillis()+"."+extension(format);
                    save(output,newName,format); successful++;
                    report.append("✓ ").append(newName).append("\n");
                } else {
                    String reason=result==null?"未知错误":result.getOutput();
                    if(reason==null)reason="转换失败";
                    if(reason.length()>120)reason=reason.substring(reason.length()-120);
                    report.append("✗ ").append(name).append("：").append(reason).append("\n");
                }
            } catch(Exception e) {
                if(!cancelled.get())report.append("✗ ").append(name).append("：").append(e.getMessage()).append("\n");
            } finally {
                session.set(null); if(input!=null)input.delete(); if(output!=null)output.delete();
            }
        }
        int done=successful; runOnUiThread(()->{
            running=false; chooseButton.setEnabled(true); convertButton.setEnabled(true);
            formatSpinner.setEnabled(true); qualitySpinner.setEnabled(true); cancelButton.setVisibility(View.GONE);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            statusText.setText(cancelled.get()?"已取消 · 成功 "+done+" 个":"完成 · 成功 "+done+"/"+jobs.size());
            if(!cancelled.get()){bar.setProgress(1000);percentText.setText(done+"/"+jobs.size()+" 个成功");}
            resultText.setText(report.length()>0?report.toString().trim():"没有生成文件");
        });
    }
    private void save(File file,String name,String format)throws Exception {
        boolean audio=isAudio(format);
        Uri collection=audio?MediaStore.Audio.Media.EXTERNAL_CONTENT_URI:
            format.equals("gif")?MediaStore.Images.Media.EXTERNAL_CONTENT_URI:MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String folder=audio?Environment.DIRECTORY_MUSIC:format.equals("gif")?Environment.DIRECTORY_PICTURES:Environment.DIRECTORY_MOVIES;
        ContentValues values=new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
        values.put(MediaStore.MediaColumns.MIME_TYPE,mime(format));
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,folder+"/CheemsConverter");
        values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri destination=getContentResolver().insert(collection,values);
        if(destination==null)throw new IllegalStateException("无法保存文件");
        boolean ok=false;
        try(InputStream in=new FileInputStream(file);OutputStream out=getContentResolver().openOutputStream(destination)){
            if(out==null)throw new IllegalStateException("无法写入文件");
            byte[] buf=new byte[128*1024];int n;
            while((n=in.read(buf))!=-1){out.write(buf,0,n);}
            ok=true;
        } finally {if(!ok)getContentResolver().delete(destination,null,null);}
        values.clear();values.put(MediaStore.MediaColumns.IS_PENDING,0);
        getContentResolver().update(destination,values,null,null);
    }
    private static String extension(String format){return format.equals("mpeg")?"mpg":format;}
    private static boolean isAudio(String f) {
        return f.equals("mp3")||f.equals("m4a")||f.equals("wav")||f.equals("flac")||
            f.equals("ogg")||f.equals("opus")||f.equals("aac");
    }
    private static String mime(String f) {
        switch(f){
            case "mp4":return "video/mp4"; case "mkv":return "video/x-matroska";
            case "mov":return "video/quicktime"; case "avi":return "video/x-msvideo";
            case "webm":return "video/webm"; case "flv":return "video/x-flv";
            case "wmv":return "video/x-ms-wmv"; case "3gp":return "video/3gpp";
            case "mpeg":return "video/mpeg"; case "ts":return "video/mp2t";
            case "gif":return "image/gif"; case "mp3":return "audio/mpeg";
            case "m4a":return "audio/mp4"; case "wav":return "audio/wav";
            case "flac":return "audio/flac"; case "ogg":return "audio/ogg";
            case "opus":return "audio/opus"; case "aac":return "audio/aac";
            default:return "application/octet-stream";
        }
    }
    private static String command(File input,File output,String format,int q) {
        int crf=q==1?18:q==2?29:23; int vp9=q==1?27:q==2?39:33;
        String cmd="-hide_banner -y -i \""+input.getAbsolutePath()+"\" ";
        if(isAudio(format)){
            String codec;
            switch(format){
                case "mp3":codec="-c:a libmp3lame -q:a "+(q==1?2:4);break;
                case "m4a":case "aac":codec="-c:a aac -b:a "+(q==1?"256k":"160k");break;
                case "wav":codec="-c:a pcm_s16le";break;
                case "flac":codec="-c:a flac";break;
                case "ogg":codec="-c:a libvorbis -q:a 5";break;
                default:codec="-c:a libopus -b:a 128k";
            }
            cmd+="-map 0:a:0 -vn "+codec+" ";
        } else if(format.equals("gif")){
            int width=q==1?720:q==2?320:480; int fps=q==1?15:q==2?8:12;
            cmd+="-an -filter_complex \"[0:v]fps="+fps+",scale="+width+":-1:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse\" -loop 0 ";
        } else {
            cmd+="-map 0:v:0? -map 0:a:0? ";
            if(q==2&&!format.equals("3gp"))
                cmd+="-vf \"scale=1280:720:force_original_aspect_ratio=decrease:force_divisible_by=2\" ";
            switch(format){
                case "mp4":case "mov":case "mkv":case "flv":case "ts":
                    cmd+="-c:v libx264 -preset veryfast -crf "+crf+" -pix_fmt yuv420p -c:a aac -b:a 160k ";
                    if(format.equals("mp4")||format.equals("mov"))cmd+="-movflags +faststart ";
                    break;
                case "webm":cmd+="-c:v libvpx-vp9 -b:v 0 -crf "+vp9+" -row-mt 1 -c:a libopus -b:a 128k ";break;
                case "avi":cmd+="-c:v mpeg4 -q:v "+(q==1?2:q==2?7:4)+" -c:a libmp3lame -q:a 4 ";break;
                case "wmv":cmd+="-c:v wmv2 -b:v 1800k -c:a wmav2 -b:a 128k ";break;
                case "3gp":cmd+="-vf \"scale=640:480:force_original_aspect_ratio=decrease:force_divisible_by=2\" -c:v mpeg4 -b:v 800k -c:a aac -b:a 96k ";break;
                case "mpeg":cmd+="-c:v mpeg2video -q:v 4 -c:a mp2 -ar 44100 -b:a 192k ";break;
                default:break;
            }
        }
        return cmd+"\""+output.getAbsolutePath()+"\"";
    }
    private void status(String s){runOnUiThread(()->statusText.setText(s));}
    @Override protected void onDestroy(){
        super.onDestroy();
        if(running){cancelled.set(true);FFmpegSession s=session.get();if(s!=null)FFmpegKit.cancel(s.getSessionId());}
        executor.shutdown();
    }
}
