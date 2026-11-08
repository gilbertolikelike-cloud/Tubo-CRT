package com.ocrautoclicker.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.text.InputType;

import java.util.ArrayList;
import java.util.List;

/** Floating click points and a compact control panel. All view changes run on the main thread. */
public class AutoClickOverlayManager {
    private static final int MAX_POINTS = 100;
    private final Context context;
    private final WindowManager wm;
    private final int screenW, screenH;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Point> points = new ArrayList<>();
    private final SharedPreferences prefs;
    private View panel;
    private TextView timerButton, playPauseButton, countLabel, statusLabel;
    private WindowManager.LayoutParams panelParams;
    private boolean running;
    private Runnable scheduler;
    private long nextDue[];
    private int clickUnit;
    private boolean soloMode;
    private boolean sequenceMode;
    private int sequenceIndex;
    private long sequenceDue;
    private long timerDurationMs;
    private long runEndsAt;
    private long nextGlobalTapAt;

    public AutoClickOverlayManager(Context c, int w, int h) {
        context=c; screenW=w; screenH=h;
        wm=(WindowManager)c.getSystemService(Context.WINDOW_SERVICE);
        prefs=c.getSharedPreferences("automation", Context.MODE_PRIVATE);
        clickUnit=prefs.getInt("click_interval_unit",prefs.getInt("global_unit",1));
        String mode=prefs.getString("click_mode","custom");
        soloMode="solo".equals(mode);
        sequenceMode="sequence".equals(mode);
        timerDurationMs=Math.max(0L,Math.min(86400000L,prefs.getLong("click_timer_duration_ms",0L)));
    }

    public void show() {
        if (panel != null) return;
        int count=soloMode?1:Math.max(1,Math.min(MAX_POINTS,prefs.getInt("point_count",3)));
        for(int i=0;i<count;i++) addPoint(false);
        // Add the controls last so target markers can never sit on top of panel buttons.
        buildPanel();
        updatePanelCount();
    }

    private WindowManager.LayoutParams params(int x,int y,int w,int h) {
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(w,h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.LEFT; p.x=x; p.y=y; return p;
    }

    private int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void buildPanel() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(7), dp(8), dp(8));
        GradientDrawable shell = new GradientDrawable();
        shell.setColor(0xF21B222B);
        shell.setCornerRadius(dp(18));
        shell.setStroke(dp(1), 0xFF526171);
        root.setBackground(shell);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(5), 0, dp(5), dp(5));
        statusLabel = new TextView(context);
        statusLabel.setTextColor(0xFFFFD166);
        statusLabel.setTextSize(12);
        statusLabel.setText("● PAUSADO");
        statusLabel.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(statusLabel, new LinearLayout.LayoutParams(0, dp(34), 1));
        countLabel = new TextView(context);
        countLabel.setTextColor(Color.WHITE);
        countLabel.setTextSize(12);
        countLabel.setGravity(Gravity.CENTER);
        countLabel.setText("0 ALVOS");
        header.addView(countLabel, new LinearLayout.LayoutParams(dp(76), dp(34)));
        TextView closeButton = actionButton("×", 0xFF8A3D43);
        closeButton.setTextSize(20);
        header.addView(closeButton, new LinearLayout.LayoutParams(dp(40), dp(34)));
        closeButton.setOnClickListener(v -> {
            // Closing the X shuts down the whole automation service, not just the panel.
            // This guarantees no hidden click loop or OCR capture keeps running.
            pauseClicks();
            context.stopService(new android.content.Intent(context, ScreenCaptureService.class));
        });
        root.addView(header);

        header.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    downX = e.getRawX(); downY = e.getRawY();
                    startX = panelParams.x; startY = panelParams.y;
                    return true;
                }
                if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    panelParams.x = Math.max(0, Math.min(screenW - dp(280),
                        startX + (int)(e.getRawX() - downX)));
                    panelParams.y = Math.max(0, Math.min(screenH - dp(155),
                        startY + (int)(e.getRawY() - downY)));
                    try { wm.updateViewLayout(panel, panelParams); } catch (Exception ignored) { }
                    return true;
                }
                return e.getAction() == MotionEvent.ACTION_UP
                    || e.getAction() == MotionEvent.ACTION_CANCEL;
            }
        });

        LinearLayout firstRow = new LinearLayout(context);
        firstRow.setOrientation(LinearLayout.HORIZONTAL);
        firstRow.setGravity(Gravity.CENTER);
        TextView plus = actionButton("+", 0xFF147D64);
        TextView minus = actionButton("−", 0xFF465568);
        playPauseButton = actionButton("▶ INICIAR", 0xFF176B45);
        firstRow.addView(plus, actionParams());
        firstRow.addView(minus, actionParams());
        firstRow.addView(playPauseButton, new LinearLayout.LayoutParams(dp(112), dp(50)));
        root.addView(firstRow);

        LinearLayout secondRow = new LinearLayout(context);
        secondRow.setOrientation(LinearLayout.HORIZONTAL);
        secondRow.setGravity(Gravity.CENTER);
        timerButton = actionButton("⏱ TEMPO", 0xFF465568);
        TextView stop = actionButton("■ PARAR", 0xFF8A3D43);
        secondRow.addView(timerButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        secondRow.addView(stop, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(secondRow);

        plus.setOnClickListener(v -> addPoint(true));
        minus.setOnClickListener(v -> removeLastPoint());
        playPauseButton.setOnClickListener(v -> {
            if (running) pauseClicks();
            else startClicks();
        });
        timerButton.setOnClickListener(v -> showTimerDialog());
        stop.setOnClickListener(v -> {
            pauseClicks();
            TapAccessibilityService.clearPendingNormalTaps();
            android.widget.Toast.makeText(context, "Cliques pausados.", android.widget.Toast.LENGTH_SHORT).show();
        });

        panel = root;
        panelParams = params(dp(12), Math.max(dp(12), screenH - dp(180)),
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
        wm.addView(panel, panelParams);
        updateTimerButton();
        updatePanelCount();
        updateRunState();
    }

    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(58), dp(50));
        p.setMargins(dp(3), dp(3), dp(3), dp(5));
        return p;
    }

    private TextView actionButton(String label, int color) {
        TextView t = new TextView(context);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(54));
        t.setMinHeight(dp(48));
        t.setPadding(dp(5), dp(4), dp(5), dp(4));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(12));
        shape.setStroke(dp(1), 0x55FFFFFF);
        t.setBackground(shape);
        t.setClickable(true);
        t.setFocusable(false);
        return t;
    }

    private void updateRunState() {
        if (statusLabel != null) {
            statusLabel.setText(running ? "● EXECUTANDO" : "● PAUSADO");
            statusLabel.setTextColor(running ? 0xFF65E6A5 : 0xFFFFD166);
        }
        if (playPauseButton != null) {
            playPauseButton.setText(running ? "Ⅱ PAUSAR" : "▶ INICIAR");
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(running ? 0xFF9B6325 : 0xFF176B45);
            bg.setCornerRadius(dp(12));
            bg.setStroke(dp(1), 0x55FFFFFF);
            playPauseButton.setBackground(bg);
        }
    }

    /** While running, point overlays must let injected accessibility taps pass through to the game. */
    private void setPointTouchability(boolean touchable) {
        for (Point point : points) {
            if (point.view == null || point.params == null) continue;
            int flags = point.params.flags;
            if (touchable) flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            if (flags != point.params.flags) {
                point.params.flags = flags;
                try { wm.updateViewLayout(point.view, point.params); }
                catch (Exception ignored) { }
            }
        }
    }

    private void addPoint(boolean save) {
        if(soloMode&&!points.isEmpty()) {
            android.widget.Toast.makeText(context, "No modo Solo só pode existir uma bolinha.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        boolean resume=running;
        if(resume)pauseClicks();
        if(points.size()>=MAX_POINTS) {
            if(resume) startClicks();
            return;
        }
        int i=points.size();
        int x=prefs.getInt("point_x_"+i,screenW/2 + ((i%5)-2)*18);
        int y=prefs.getInt("point_y_"+i,screenH/2 + ((i%7)-3)*18);
        Point point=new Point(i);
        TextView dot=actionButton(String.valueOf(i+1), 0xFF087FBF);
        dot.setTextSize(12);
        GradientDrawable shape=new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(0xCC00A6FF); shape.setStroke(2,Color.WHITE);
        dot.setBackground(shape);
        point.view=dot; point.params=params(x,y,44,44);
        wm.addView(dot,point.params);
        dot.setOnTouchListener(new View.OnTouchListener(){
            float downX,downY; int startX,startY; long downAt;
            @Override public boolean onTouch(View v,MotionEvent e){
                if(e.getAction()==MotionEvent.ACTION_DOWN){
                    downX=e.getRawX();downY=e.getRawY();startX=point.params.x;startY=point.params.y;
                    downAt=android.os.SystemClock.elapsedRealtime();return true;
                }
                if(e.getAction()==MotionEvent.ACTION_MOVE){
                    int dx=(int)(e.getRawX()-downX),dy=(int)(e.getRawY()-downY);
                    if(Math.abs(dx)>3||Math.abs(dy)>3){
                        point.params.x=Math.max(0,Math.min(screenW-44,startX+dx));
                        point.params.y=Math.max(0,Math.min(screenH-44,startY+dy));
                        wm.updateViewLayout(point.view,point.params);
                    } return true;
                }
                if(e.getAction()==MotionEvent.ACTION_UP){
                    int dx=(int)(e.getRawX()-downX),dy=(int)(e.getRawY()-downY);
                    if(Math.abs(dx)<8&&Math.abs(dy)<8
                        &&android.os.SystemClock.elapsedRealtime()-downAt>550) showPointInterval(point);
                    else savePoint(point);
                    return true;
                }
                return true;
            }
        });
        points.add(point);
        if(save){prefs.edit().putInt("point_count",points.size()).apply();updatePanelCount();}
        if(resume)startClicks();
    }

    private void removeLastPoint() {
        if(soloMode&&points.size()<=1) {
            android.widget.Toast.makeText(context, "No modo Solo a bolinha única não pode ser removida.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        boolean resume=running;
        if(resume)pauseClicks();
        if(points.isEmpty()) return;
        Point p=points.remove(points.size()-1);
        try{wm.removeView(p.view);}catch(Exception ignored){}
        prefs.edit().putInt("point_count",points.size())
            .remove("point_x_"+p.index).remove("point_y_"+p.index)
            .remove("point_interval_ms_"+p.index).apply();
        updatePanelCount();
        if(resume&&!points.isEmpty())startClicks();
    }

    private void updatePanelCount() {
        if (countLabel != null) countLabel.setText(points.size() + " ALVOS");
    }

    private void savePoint(Point p) {
        prefs.edit().putInt("point_x_"+p.index,p.params.x)
            .putInt("point_y_"+p.index,p.params.y).apply();
    }

    private void updateTimerButton() {
        if(timerButton==null)return;
        timerButton.setText(timerDurationMs<=0?"⏱ TEMPO":"⏱ "+Math.max(1L,timerDurationMs/60000L)+" MIN");
    }

    private void showTimerDialog() {
        EditText input=new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(timerDurationMs<=0?"0":String.valueOf(Math.max(1L,timerDurationMs/60000L)));
        AlertDialog dialog=new AlertDialog.Builder(context)
            .setTitle("Duração das bolinhas")
            .setMessage("Tempo em minutos. Use 0 para executar sem limite de tempo.")
            .setView(input)
            .setNegativeButton("Cancelar",null)
            .setPositiveButton("Salvar",(d,w)->{
                try{
                    long minutes=Long.parseLong(input.getText().toString().trim());
                    if(minutes<0||minutes>1440)throw new NumberFormatException();
                    timerDurationMs=minutes*60000L;
                    prefs.edit().putLong("click_timer_duration_ms",timerDurationMs).apply();
                    updateTimerButton();
                }catch(Exception ignored){
                    android.widget.Toast.makeText(context,"Informe de 0 a 1440 minutos.",android.widget.Toast.LENGTH_SHORT).show();
                }
            }).create();
        if(dialog.getWindow()!=null)dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        dialog.show();
    }

    private long globalIntervalMs() {
        return Math.max(250L,Math.min(86400000L,prefs.getLong("click_default_interval_ms",prefs.getLong("global_interval_ms",2000L))));
    }

    private long intervalFor(Point p) {
        return Math.max(250L,Math.min(86400000L,
            prefs.getLong("point_interval_ms_"+p.index,globalIntervalMs())));
    }

    private void showPointInterval(Point p) {
        long ms=intervalFor(p);
        long multiplier=clickUnit==0?1L:(clickUnit==1?1000L:60000L);
        long shown=Math.max(1,ms/multiplier);
        EditText input=new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(shown));
        AlertDialog dialog=new AlertDialog.Builder(context)
            .setTitle("Intervalo da bolinha "+(p.index+1))
            .setMessage("Unidade atual: "+(clickUnit==0?"MS":clickUnit==1?"segundos":"minutos"))
            .setView(input)
            .setNegativeButton("Cancelar",null)
            .setPositiveButton("Salvar",(d,w)->{
                try{
                    long v=Long.parseLong(input.getText().toString().trim());
                    if(v<1)return;
                    long mult=clickUnit==0?1L:(clickUnit==1?1000L:60000L);
                    long value=Math.max(250L,Math.min(86400000L,v*mult));
                    prefs.edit().putLong("point_interval_ms_"+p.index,value).apply();
                }catch(Exception ignored){}
            }).create();
        if(dialog.getWindow()!=null)dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        dialog.show();
    }

    private void startClicks() {
        if(running)return;
        if(points.isEmpty()) {
            android.widget.Toast.makeText(context, "Adicione pelo menos uma bolinha.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if(!TapAccessibilityService.isReady()) {
            android.widget.Toast.makeText(context, "Ative a acessibilidade do OCR Auto Clicker.", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        // Critical: touchable overlay circles were intercepting the injected taps.
        // Make only the click circles transparent to touch while automation is running.
        setPointTouchability(false);
        running=true;
        updateRunState();
        nextDue=new long[points.size()];
        long now=android.os.SystemClock.elapsedRealtime();
        runEndsAt=timerDurationMs>0?now+timerDurationMs:0L;
        sequenceIndex=0;
        sequenceDue=now;
        nextGlobalTapAt=now;
        for(int i=0;i<points.size();i++) nextDue[i]=now+(i*35L);
        scheduler=new Runnable(){
            @Override public void run(){
                if(!running)return;
                long t=android.os.SystemClock.elapsedRealtime();
                if(runEndsAt>0L&&t>=runEndsAt){
                    pauseClicks();
                    android.widget.Toast.makeText(context,"Tempo configurado finalizado.",android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                long soon=Long.MAX_VALUE;
                if(sequenceMode){
                    // Sequencial: respeita o intervalo individual e limita a taxa total.
                    if(!points.isEmpty()&&t>=sequenceDue&&t>=nextGlobalTapAt){
                        if(sequenceIndex>=points.size())sequenceIndex=0;
                        Point p=points.get(sequenceIndex);
                        TapAccessibilityService.tap(p.params.x+22,p.params.y+22);
                        sequenceIndex=(sequenceIndex+1)%points.size();
                        sequenceDue=t+intervalFor(p);
                        nextGlobalTapAt=t+120L;
                    }
                    soon=Math.min(sequenceDue,nextGlobalTapAt);
                }else{
                    // Personalizado: um toque por vez; evita rajadas que aquecem o aparelho.
                    boolean sent=false;
                    for(int i=0;i<points.size();i++){
                        if(i>=nextDue.length)break;
                        Point p=points.get(i);
                        if(!sent&&t>=nextDue[i]&&t>=nextGlobalTapAt){
                            TapAccessibilityService.tap(p.params.x+22,p.params.y+22);
                            nextDue[i]=t+intervalFor(p);
                            nextGlobalTapAt=t+120L;
                            sent=true;
                        }
                        soon=Math.min(soon,nextDue[i]);
                    }
                    if(!sent){
                        for(int i=0;i<nextDue.length;i++){
                            if(t>=nextDue[i]){soon=Math.min(soon,nextGlobalTapAt);break;}
                        }
                    }
                }
                if(running){
                    long delay=Math.max(10L,Math.min(250L,soon-android.os.SystemClock.elapsedRealtime()));
                    main.postDelayed(this,delay);
                }
            }
        };
        main.post(scheduler);
    }

    private void pauseClicks() {
        running=false;
        TapAccessibilityService.clearPendingNormalTaps();
        runEndsAt=0L;
        if(scheduler!=null)main.removeCallbacks(scheduler);
        scheduler=null;nextDue=null;sequenceIndex=0;sequenceDue=0L;nextGlobalTapAt=0L;
        // Restore touch input so the user can drag and configure the points again.
        setPointTouchability(true);
        updateRunState();
    }

    public void destroy() {
        pauseClicks();
        for(Point p:points)try{wm.removeView(p.view);}catch(Exception ignored){}
        points.clear();
        if(panel!=null)try{wm.removeView(panel);}catch(Exception ignored){}
        panel=null;
    }

    private static class Point {
        final int index;
        View view;
        WindowManager.LayoutParams params;
        Point(int i){index=i;}
    }
}
