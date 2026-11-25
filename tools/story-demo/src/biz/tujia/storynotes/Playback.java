package biz.tujia.storynotes;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.KeyCharacterMap;
import android.view.View;
import android.content.Context;
import android.view.ViewConfiguration;
import android.view.inputmethod.InputMethodManager;
import java.text.Normalizer;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Drives ordinary controls in a separate installed keyboard and checks the saved note. */
public final class Playback extends Instrumentation {
    private UiAutomation ui;
    private NotesActivity host;
    private Bundle options;
    private AccessibilityNodeInfo keyboard;
    private final Map<String, Rect> keyPositions = new HashMap<>();
    private final StringBuilder expected = new StringBuilder();
    private boolean fastCards;
    private boolean readingPace;
    private static final String YE_BUTTON = "叶氏拼音";
    private static final String MARKED_BUTTON = "调号拼音";
    private static final String HAN_BUTTON = "谐音字";
    private String spellingButton = YE_BUTTON;
    private final StringBuilder filmTrace=new StringBuilder();
    private boolean afterSymbolPage;
    private final Map<Integer,Rect> candidateTiles=new HashMap<>();
    private static final String IME = "biz.tujia.ime";

    @Override public void onCreate(Bundle args) { super.onCreate(args); options = args; start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_CANCELED;
        try {
            ui = getUiAutomation();
            AccessibilityServiceInfo info = ui.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
            info.notificationTimeout = 0;
            ui.setServiceInfo(info);
            Intent start = new Intent(getTargetContext(), NotesActivity.class);
            start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            if ("1".equals(options.getString("reading_pace")))
                start.putExtra("notebook","reading-"+System.currentTimeMillis());
            host = (NotesActivity) startActivitySync(start);
            waitForIdleSync();
            play();
            result.putString("outcome", "PASS");
            code = Activity.RESULT_OK;
        } catch (Throwable ex) {
            result.putString("outcome","FAIL");
            result.putString("failure", Log.getStackTraceString(ex));
        } finally {
            if (keyboard != null) keyboard.recycle();
            finish(code, result);
        }
    }

    private File file(String name) { return new File(getTargetContext().getFilesDir(), name); }

    private List<String[]> readScript() throws IOException {
        String name = options.getString("script", "story.tsv");
        ArrayList<String[]> list = new ArrayList<>();
        try (BufferedReader input = new BufferedReader(new InputStreamReader(new FileInputStream(file(name)), StandardCharsets.UTF_8))) {
            String line;
            while ((line=input.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("keys\ttext\t")) continue;
                String[] f=line.split("\t",-1);
                if (f.length != 4 && f.length!=5) throw new IOException("Invalid story row " + (list.size()+1));
                if (f.length==5 && !f[1].equals(f[4].replace("|","")))
                    throw new IOException("Syllable units differ from intended output on row " + (list.size()+1));
                f[3] = f[3].replace("\\n","\n");
                list.add(f);
            }
        }
        if (list.isEmpty()) throw new IOException("Empty story");
        return list;
    }

    private void stage(String key, String message) {
        Bundle status = new Bundle();
        status.putString(key, message);
        sendStatus(1, status);
    }

    private int shotIndex;
    private boolean captureStates;
    private void shot(String tag) {
        if (!captureStates) return;
        saveShot(tag,ui.takeScreenshot());
    }

    private void saveShot(String tag,android.graphics.Bitmap frame) {
        if (frame==null) throw new AssertionError("Could not capture the device screen");
        File dir=file("recorded-frames");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Cannot create frame directory");
        File path=new File(dir,String.format(Locale.ROOT,"%05d-%s.png",shotIndex++,tag));
        try (OutputStream out=new FileOutputStream(path)) {
            if (!frame.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out)) throw new IOException("Screenshot encoding failed");
        } catch(IOException ex) { throw new IllegalStateException(ex); }
        finally { frame.recycle(); }
    }

    private void trace(String kind,String value) {
        if (readingPace) filmTrace.append(SystemClock.elapsedRealtimeNanos()).append('\t')
                .append(kind).append('\t').append(value).append('\n');
    }

    private void segment(int n) throws IOException {
        if (!"1".equals(options.getString("record")) || n < Integer.parseInt(options.getString("capture_from","0"))) return;
        stage("record_segment", Integer.toString(n));
        File gate = file("record-go-" + n);
        long end = SystemClock.uptimeMillis() + 120000;
        while (!gate.isFile() && SystemClock.uptimeMillis() < end) SystemClock.sleep(100);
        if (!gate.isFile()) throw new IOException("Recorder did not acknowledge segment " + n);
    }

    private void play() throws Exception {
        List<String[]> script = readScript();
        fastCards="1".equals(options.getString("fast_cards"));
        readingPace="1".equals(options.getString("reading_pace"));
        ArrayList<Integer> ranks=new ArrayList<>();
        if (fastCards) {
            try (BufferedReader r=new BufferedReader(new FileReader(file("story-ranks.txt")))) {
                String line;
                while ((line=r.readLine())!=null) ranks.add(Integer.parseInt(line));
            }
            if (ranks.size()!=script.size()) throw new AssertionError("Candidate rank list mismatch");
        }
        int chunk = Integer.parseInt(options.getString("chunk", "18"));
        int start=0;
        long restoreId=0;
        if ("1".equals(options.getString("resume")) && file("story-checkpoint.txt").isFile()) {
            try (BufferedReader state = new BufferedReader(new FileReader(file("story-checkpoint.txt")))) {
                start=Integer.parseInt(state.readLine());
                restoreId=Long.parseLong(state.readLine());
            }
            if (start%chunk!=0 || start>script.size()) throw new IOException("Invalid recording checkpoint");
            for (int j=0;j<start;j++) expected.append(script.get(j)[1]).append(script.get(j)[3]);
            if (!expected.toString().equals(host.saved(restoreId))) throw new IOException("Saved checkpoint differs from script");
        }
        if (start==0 && !readingPace) segment(0);
        SystemClock.sleep(400);
        if (restoreId==0) tap(viewPosition(host.action), 70);
        else tap(appLabel("打开笔记 "+restoreId),70);
        waitForEditor();
        long noteId = host.currentId;
        expectEditor(expected.toString());
        boolean styleDemo = "1".equals(options.getString("style_demo"));
        String currentStyle = styleDemo ? spellingStyle(script.get(0)[1]) : "";
        String mode = options.getString("mode","汉字记音");
        if (readingPace) {
            playReading(script, noteId, chunk);
            return;
        }
        if (styleDemo) {
            changeSpellingStyle(currentStyle);
            openStyleKeyboard(mode);
        } else {
            openKeyboard(mode);
            cacheRow("qwertyuiop");
            cacheRow("asdfghjkl");
            cacheRow("zxcvbnm");
        }
        if (start>0) moveCaretToEnd();
        for (int i=start; i<script.size(); i++) {
            if (i > 0 && i % chunk == 0) {
                runOnMainSync(host::storeDraft);
                try (PrintWriter state=new PrintWriter(new FileWriter(file("story-checkpoint.txt")))) {
                    state.println(i);
                    state.println(noteId);
                    if (state.checkError()) throw new IOException("Could not write recording checkpoint");
                }
                segment(i/chunk);
            }
            String[] step=script.get(i);
            if (styleDemo && !currentStyle.equals(spellingStyle(step[1]))) {
                press("隐藏键盘",70);
                currentStyle=spellingStyle(step[1]);
                changeSpellingStyle(currentStyle);
                openStyleKeyboard(mode);
            }
            String prior=expected.toString();
            for (int attempt=0;attempt<2;attempt++) {
                try {
                    if (i==0 && attempt==0) stage("first_keys","1");
                    if (attempt>0) correctTo(prior,mode);
                    if (styleDemo) typeAfterSettings(step[0]);
                    else if (attempt>0 || afterSymbolPage) typeConfirmed(step[0]);
                    else type(step[0],readingPace || i<2);
                    afterSymbolPage=false;
                    if (fastCards && i>=2) chooseRanked(step[0],step[1],step[2],ranks.get(i));
                    else choose(step[1],step[2],readingPace || i<2);
                    expected.append(step[1]);
                    expectEditor(expected.toString());
                    appendPunctuation(step[3]);
                    expectEditor(expected.toString());
                    break;
                } catch (AssertionError error) {
                    if (attempt==1) throw new AssertionError("Step "+(i+1)+"/"+script.size()+" ["+step[0]+", "+step[1]+"]",error);
                    stage("recovering","Row "+(i+1)+" "+error.getMessage());
                    expected.setLength(prior.length());
                }
            }
            if (i < 3 || i % 5 == 4 || i+1 == script.size()) stage("words", (i+1) + "/" + script.size());
        }
        saveAndReopen(noteId, script.size());
    }

    private String modeLabel(String mode) {
        if ("spell".equals(mode)) return "土家语拼写";
        if ("han".equals(mode)) return "汉字记音";
        if ("hand".equals(mode)) return "手写";
        throw new IllegalArgumentException("Unknown recording mode " + mode);
    }

    private void warmHandwriting() {
        press("设置",100);
        press("汉字",100);
        Rect warmPad=require("汉字手写区");
        float side=.88f*Math.min(warmPad.width(),warmPad.height());
        for (int i=0;i<3;i++) {
            float y=warmPad.exactCenterY()+side*(i*.29f-.3f);
            float half=side*(i==2 ? .4f : .25f);
            drawLine(warmPad.exactCenterX()-half,y,warmPad.exactCenterX()+half,y);
        }
        // Warm the real recognition worker, then remove its real host commit.
        long warmUntil=SystemClock.uptimeMillis()+90000;
        while (!editorText().equals("三") && SystemClock.uptimeMillis()<warmUntil) SystemClock.sleep(110);
        expectEditor("三");
        press("汉字记音",100);
        press("退格",100);
        expectEditor("");
    }

    private void playReading(List<String[]> script,long noteId,int chunk) throws Exception {
        String current = script.get(0)[0].split(":",2)[0];
        boolean hasSpelling=false, hasHandwriting=false;
        for (String[] row:script) {
            hasSpelling|=row[0].startsWith("spell:");
            hasHandwriting|=row[0].startsWith("hand:");
        }
        if (hasSpelling) changeSpellingStyle("带调号拼音");
        openStyleKeyboard(hasHandwriting ? "手写" : modeLabel(current));
        if (hasHandwriting) warmHandwriting();
        press(modeLabel(current),100);
        press("隐藏键盘",100);
        SystemClock.sleep(700);
        segment(0);
        captureStates="1".equals(options.getString("keyframes"));
        shot("opening");
        trace("record-ready",modeLabel(current));
        stage("chapter",modeLabel(current));
        SystemClock.sleep(850);
        openStyleKeyboard(modeLabel(current));
        shot("ready");
        boolean spaceBefore=false;
        for (int i=0;i<script.size();i++) {
            if (i>0 && i%chunk==0) segment(i/chunk);
            String[] step=script.get(i);
            String[] input=step[0].split(":",2);
            if (input.length!=2) throw new IOException("Missing input mode for demonstration row "+(i+1));
            String mode=input[0], keys=input[1];
            if (!mode.equals(current)) {
                String name=modeLabel(mode);
                stage("chapter",name);
                trace("mode-start",name);
                press(name,230);
                trace("mode-selected",name);
                SystemClock.sleep(1100);
                current=mode;
            }
            shot("begin"+i);
            if (mode.equals("hand")) {
                trace("word-start",keys);
                drawGlyph(keys);
                trace("word-end",keys);
                expectEditor(expected.toString()+keys);
                shot("auto"+i);
                stage("automatic", keys);
                if (keys.equals(step[1])) {
                    // An optional hint is observed, without pressing any candidate.
                    if (!step[2].isEmpty()) require(step[2]);
                } else {
                    SystemClock.sleep(1300);
                    choose(step[1],step[2],true);
                }
            } else if (keys.equals("@next")) {
                requireEmptyDraft();
                String label="下一音节 "+(step[2].isEmpty() ? step[1] : choiceDescription(step[1],step[2]));
                Rect offered=require(label);
                shot("choices"+i);
                trace("sound-offered",step[1]);
                SystemClock.sleep(1200);
                tap(offered,170);
                requireEmptyDraft();
                trace("sound-committed",step[1]);
            } else {
                trace("word-start",keys);
                typeAfterSettings(keys);
                trace("word-end",keys);
                shot("choices"+i);
                choose(step[1],step[2],true);
            }
            if (spaceBefore) expected.append(' ');
            expected.append(step[1]);
            expectEditor(expected.toString());
            shot("commit"+i);
            trace("commit",step[1]);
            stage("selected",step[1]);
            SystemClock.sleep(1200);
            spaceBefore=step[3].equals(" ");
            if (!spaceBefore && !step[3].isEmpty()) {
                if (mode.equals("hand")) {
                    press("汉字记音",200);
                    current="han";
                    SystemClock.sleep(600);
                }
                appendPunctuation(step[3]);
                expectEditor(expected.toString());
                if (step[3].contains("。")) SystemClock.sleep(2000);
            }
            shot("after"+i);
            stage("words",(i+1)+"/"+script.size());
        }
        stage("chapter","保存并重开");
        saveAndReopen(noteId,script.size());
        try (OutputStream out=new FileOutputStream(file("film-events.tsv"))) {
            out.write(filmTrace.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void drawGlyph(String glyph) throws IOException {
        Rect pad=require("汉字手写区");
        String line=null;
        try (BufferedReader r=new BufferedReader(new FileReader(file("stroke-demo.tsv")))) {
            String next;
            while ((next=r.readLine())!=null) {
                if (next.startsWith(glyph+"\t")) { line=next; break; }
            }
        }
        if (line==null) throw new IOException("Missing recorded stroke path for "+glyph);
        float side=.88f*Math.min(pad.width(),pad.height());
        int strokeNumber=0;
        for (String stroke:line.split("\t",2)[1].split(";")) {
            strokeNumber++;
            long down=SystemClock.uptimeMillis();
            String[] points=stroke.split(" ");
            for (int j=0;j<points.length;j++) {
                String[] xy=points[j].split(",");
                float x=pad.exactCenterX()+(Float.parseFloat(xy[0])/1024f-.5f)*side;
                float y=pad.exactCenterY()+(Float.parseFloat(xy[1])/1024f-.5f)*side;
                int action=j==0 ? MotionEvent.ACTION_DOWN : j==points.length-1 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
                if (captureStates && j==points.length-1) {
                    // Capture the true final movement before lifting. Capturing after
                    // an UP could let the automatic character boundary expire.
                    injectStroke(down,MotionEvent.ACTION_MOVE,x,y);
                    shot("ink"+strokeNumber);
                }
                if (j==0 || j==points.length-1)
                    trace(j==0 ? "stroke-down" : "stroke-up",Integer.toString(strokeNumber));
                // Finish the pen lift promptly so a multi-stroke character is
                // not mistaken for several separate characters on a slow AVD.
                inject(down,action,x,y);
                SystemClock.sleep(20);
            }
            SystemClock.sleep(55);
        }
        if (!pad.equals(require("汉字手写区"))) throw new AssertionError("Handwriting panel changed bounds");
    }

    private void drawLine(float x1,float y1,float x2,float y2) {
        long down=SystemClock.uptimeMillis();
        for (int j=0;j<=6;j++) {
            float t=j/6f;
            int action=j==0 ? MotionEvent.ACTION_DOWN : j==6 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            injectStroke(down,action,x1+(x2-x1)*t,y1+(y2-y1)*t);
        }
    }

    private void injectStroke(long down,int action,float x,float y) {
        MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            if (!ui.injectInputEvent(e,true)) throw new AssertionError("Stylus gesture rejected");
        } finally { e.recycle(); }
    }

    private void saveAndReopen(long noteId,int wordCount) throws IOException {
        SystemClock.sleep(800);
        press("隐藏键盘", 75);
        SystemClock.sleep(500);
        tap(viewPosition(host.action),90);
        long until=SystemClock.uptimeMillis()+8000;
        while (!expected.toString().equals(host.saved(noteId)) && SystemClock.uptimeMillis()<until) SystemClock.sleep(70);
        if (!expected.toString().equals(host.saved(noteId))) throw new AssertionError("Saved content differs from typed text");
        SystemClock.sleep(900);
        Rect item = appLabel("打开笔记 " + noteId);
        tap(item,90);
        waitForIdleSync();
        expectEditor(expected.toString());
        final String[] saved = new String[1];
        runOnMainSync(() -> saved[0]=host.editor.getText().toString());
        try (OutputStream out = new FileOutputStream(file("verified-note.txt"))) {
            out.write(saved[0].getBytes(StandardCharsets.UTF_8));
        }
        shot("saved");
        stage("saved", wordCount + " selections; " + saved[0].length() + " UTF-16 units, verified after reopen");
        SystemClock.sleep(readingPace ? 4200 : 800);
        reviewSavedText();
        if ("1".equals(options.getString("record"))) {
            stage("record_done", "1");
            File gate=file("record-finalized");
            long untilDone=SystemClock.uptimeMillis()+60000;
            while (!gate.isFile() && SystemClock.uptimeMillis()<untilDone) SystemClock.sleep(100);
            if (!gate.isFile()) throw new IOException("Recorder did not finalize before closing the note");
        }
    }

    private void reviewSavedText() {
        // The reopened editor starts at the top. Scroll through its real layout.
        int[] height=new int[2];
        runOnMainSync(() -> {
            height[0]=host.editor.getHeight() - host.editor.getCompoundPaddingTop() - host.editor.getCompoundPaddingBottom();
            height[1]=host.editor.getLayout().getHeight();
        });
        if (height[0] <= 0) return;
        int step=Math.max(1,height[0]*3/4);
        for (int y=step; y<height[1]; y+=step) {
            final int target=Math.min(y,Math.max(0,height[1]-height[0]));
            runOnMainSync(() -> host.editor.scrollTo(0,target));
            SystemClock.sleep(1050);
            if (target >= height[1]-height[0]) break;
        }
        SystemClock.sleep(950);
    }

    private void changeSpellingStyle(String style) {
        Intent settings = new Intent().setClassName(IME, IME + ".SetupActivity");
        settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        runOnMainSync(() -> host.startActivity(settings));
        Rect open = appContaining("打字输出 ·");
        SystemClock.sleep(500);
        tap(open,90);
        spellingButton = "带调号拼音".equals(style) ? MARKED_BUTTON : YE_BUTTON;
        Rect row = appLabel(spellingButton + ("带调号拼音".equals(style) ? "，bízīkǎ" : "，bifzixkar"));
        SystemClock.sleep(850);
        tap(row,90);
        appLabel("打字输出 · " + spellingButton);
        SystemClock.sleep(700);
        long t=SystemClock.uptimeMillis();
        ui.injectInputEvent(new KeyEvent(t,t,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK,0),true);
        ui.injectInputEvent(new KeyEvent(t,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK,0),true);
        appLabel("保存");
        long deadline = SystemClock.uptimeMillis() + 10000;
        boolean[] focused = {false};
        do {
            runOnMainSync(() -> focused[0] = host.hasWindowFocus());
            if (focused[0]) break;
            SystemClock.sleep(40);
        } while (SystemClock.uptimeMillis() < deadline);
        if (!focused[0]) throw new AssertionError("Note did not regain focus after changing spelling");
        if (keyboard != null) { keyboard.recycle(); keyboard = null; }
    }

    private static String spellingStyle(String expectedWord) {
        String decomposed=Normalizer.normalize(expectedWord, Normalizer.Form.NFD);
        for (int i=0; i<decomposed.length(); i++)
            if (Character.getType(decomposed.charAt(i)) == Character.NON_SPACING_MARK) return "带调号拼音";
        return "叶氏拼音";
    }

    private void openStyleKeyboard(String mode) {
        Rect editor=viewPosition(host.editor);
        int startX=editor.left + Math.min(editor.width()/2, 100);
        int startY=editor.top + Math.min(editor.height()/2, 80);
        tap(new Rect(startX-1,startY-1,startX+1,startY+1),110);
        waitForIdleSync();
        runOnMainSync(() -> {
            host.editor.requestFocus();
            host.editor.setSelection(host.editor.length());
            InputMethodManager imm=(InputMethodManager)host.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(host.editor, InputMethodManager.SHOW_IMPLICIT);
        });
        press(mode,100);
        if ("手写".equals(mode)) require("汉字手写区");
        else requireEmptyDraft();
    }

    private void typeAfterSettings(String raw) {
        // Resolve the actual key again after returning from system settings.
        for (int i=0; i<raw.length(); i++) {
            String symbol=raw.substring(i,i+1);
            trace("letter-start",raw.substring(0,i+1));
            String base=Normalizer.normalize(symbol, Normalizer.Form.NFD).substring(0,1);
            if (!base.equals(symbol)) {
                Rect vowel=require(base);
                float x=vowel.exactCenterX(), y=vowel.exactCenterY();
                long down=SystemClock.uptimeMillis();
                // A software-rendered device can deliver a queued DOWN late.
                // Keep the finger down through the actual arrival of the palette.
                injectStroke(down,MotionEvent.ACTION_DOWN,x,y);
                try {
                    SystemClock.sleep(ViewConfiguration.getLongPressTimeout()+200);
                    require(symbol);
                } finally {
                    injectStroke(down,MotionEvent.ACTION_UP,x,y);
                }
                Rect tone=require(symbol);
                shot("tones");
                SystemClock.sleep(700);
                tap(tone,180);
            } else {
                Rect key=keyPositions.get(symbol);
                if (key==null) {
                    key=require(symbol);
                    keyPositions.put(symbol,key);
                }
                tap(key,readingPace ? 100 : 155);
                String prefix=raw.substring(0,i+1);
                if (!waitDraft(prefix,1800)) {
                    Rect cancel=lookupKeyboard("取消选声调");
                    if (cancel!=null) tap(cancel,45);
                    if (raw.substring(0,i).equals(draft())) tap(key,45);
                }
            }
            trace("letter-released",raw.substring(0,i+1));
            require("待选拼写 " + raw.substring(0,i+1));
            shot("draft");
            trace("preedit",raw.substring(0,i+1));
            SystemClock.sleep(readingPace ? 220 : 150);
        }
    }

    private Rect appContaining(String query) {
        long end=SystemClock.uptimeMillis()+12000;
        do {
            AccessibilityNodeInfo r=ui.getRootInActiveWindow();
            if (r!=null) {
                try {
                    for (AccessibilityNodeInfo n:r.findAccessibilityNodeInfosByText(query)) {
                        try {
                            if (!n.refresh() || !n.isEnabled() || !n.isVisibleToUser()) continue;
                            Rect pos=new Rect(); n.getBoundsInScreen(pos);
                            if (!pos.isEmpty()) return pos;
                        } finally { n.recycle(); }
                    }
                } finally { r.recycle(); }
            }
            SystemClock.sleep(65);
        } while (SystemClock.uptimeMillis()<end);
        throw new AssertionError("Missing settings control " + query);
    }

    private void openKeyboard(String mode) {
        tap(viewPosition(host.editor),65);
        long end=SystemClock.uptimeMillis()+75000;
        long retryAt=SystemClock.uptimeMillis()+1800;
        do {
            if (lookupKeyboard("隐藏键盘") != null) { press(mode,30); return; }
            if (SystemClock.uptimeMillis()>=retryAt) {
                runOnMainSync(() -> {
                    host.editor.requestFocus();
                    android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager)host.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                    imm.showSoftInput(host.editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                });
                retryAt=SystemClock.uptimeMillis()+2500;
            }
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis()<end);
        throw new AssertionError("System did not open the selected keyboard");
    }

    private Rect viewPosition(View view) {
        final Rect r=new Rect();
        runOnMainSync(() -> {
            int[] p=new int[2]; view.getLocationOnScreen(p);
            r.set(p[0],p[1],p[0]+view.getWidth(),p[1]+view.getHeight());
        });
        return r;
    }

    private void type(String raw, boolean slow) {
        raw=raw.toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) throw new IllegalArgumentException("Missing keyboard spelling");
        // Show the first words on the soft keys. During the long middle section,
        // send standard hardware key events through Android's IME dispatch.
        if (slow || "touch".equals(options.getString("input"))) {
            clearDraft();
            for (int i=0;i<raw.length();i++) letter(raw.charAt(i),slow);
        } else {
            KeyEvent[] keys=KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(raw.toCharArray());
            if (keys==null) throw new AssertionError("Keyboard cannot source " + raw);
            for (KeyEvent key:keys) {
                if (!ui.injectInputEvent(key,false)) throw new AssertionError("Key injection failed");
            }
        }
        if (fastCards && !slow) return;
        verifyTyped(raw);
    }

    private void verifyTyped(String raw) {
        if (waitDraft(raw,2500)) return;
        // Rendering and injected touch queues can run at different rates on a software AVD.
        // Read the IME's own preedit, then correct missed letters using real keys.
        String actual=draft();
        stage("retyping", raw+" after "+actual);
        if (actual==null || !raw.startsWith(actual)) {
            clearDraft();
            actual="";
        }
        for (int i=actual.length();i<raw.length();i++) {
            letter(raw.charAt(i),false);
            if (!waitDraft(raw.substring(0,i+1),3500))
                throw new AssertionError("Draft did not reach " + raw.substring(0,i+1) + "; actual="+draft());
        }
    }

    private void letter(char c, boolean slow) {
        Rect key=keyPositions.get(String.valueOf(c));
        if (key==null) throw new IllegalArgumentException("Unsupported key "+c);
        tap(key,readingPace ? 310 : slow ? 95 : 18);
        if (slow) SystemClock.sleep(readingPace ? 220 : 75);
    }

    private String draft() {
        if (keyboard==null) lookupKeyboard("隐藏键盘");
        if (keyboard==null) return null;
        for (AccessibilityNodeInfo n:keyboard.findAccessibilityNodeInfosByText("待选拼写")) {
            try {
                if (!n.refresh()) continue;
                CharSequence value=n.getContentDescription();
                if (value!=null && value.toString().startsWith("待选拼写 ")) return value.toString().substring(5);
            } finally { n.recycle(); }
        }
        // A blank preview is hidden in the compact toolbar. An alphabet key
        // distinguishes this state from a hidden IME or an open menu.
        return lookupKeyboard("q") == null ? null : "";
    }

    private void requireEmptyDraft() {
        require("q");
        if (!waitDraft("",3500)) throw new AssertionError("Expected an empty draft; got " + draft());
    }

    private boolean waitDraft(String value,long timeout) {
        long end=SystemClock.uptimeMillis()+timeout;
        do {
            if (value.equals(draft())) return true;
            SystemClock.sleep(65);
        } while (SystemClock.uptimeMillis()<end);
        return false;
    }

    private void clearDraft() {
        if (waitDraft("",450)) return;
        String raw=draft();
        if (raw==null) throw new AssertionError("Missing preedit");
        while (!raw.isEmpty()) {
            press("退格",20);
            raw=raw.substring(0,raw.length()-1);
            if (!waitDraft(raw,3500)) throw new AssertionError("Could not clear stale draft; got "+draft());
        }
        expectEditor(expected.toString());
    }

    private void typeConfirmed(String raw) {
        clearDraft();
        for (int j=0;j<raw.length();j++) {
            press(raw.substring(j,j+1),25);
            if (!waitDraft(raw.substring(0,j+1),4500)) throw new AssertionError("Key did not reach composition: "+draft());
        }
    }

    private String editorText() {
        String[] text=new String[1];
        runOnMainSync(() -> text[0]=host.editor.getText().toString());
        return text[0];
    }

    private void moveCaretToEnd() {
        runOnMainSync(() -> {
            int end=host.editor.length();
            if (host.editor.getSelectionStart()!=end || host.editor.getSelectionEnd()!=end)
                host.editor.setSelection(end);
        });
    }

    private void correctTo(String prior,String mode) {
        if (lookupKeyboard("隐藏键盘")==null) openKeyboard(mode);
        // Reopening by tapping existing text can move Android's insertion point.
        moveCaretToEnd();
        if (lookupKeyboard("q")==null) {
            press("字母或符号",22);
            require("q");
        }
        String pending=draft();
        if (pending==null) throw new AssertionError("Cannot confirm current composition before correcting");
        while (!pending.isEmpty()) {
            press("退格",22);
            pending=pending.substring(0,pending.length()-1);
            if (!waitDraft(pending,4000)) throw new AssertionError("Cannot correct current composition");
        }
        String actual=editorText();
        if (!actual.startsWith(prior)) throw new AssertionError("Previously verified text changed");
        while (!actual.equals(prior)) {
            String shorter=actual.substring(0,actual.offsetByCodePoints(actual.length(),-1));
            press("退格",22);
            expectEditor(shorter);
            actual=shorter;
        }
    }

    private void cacheRow(String letters) {
        Rect first=require(letters.substring(0,1));
        Rect last=require(letters.substring(letters.length()-1));
        for (int i=0;i<letters.length();i++) {
            int x=first.centerX()+(last.centerX()-first.centerX())*i/(letters.length()-1);
            keyPositions.put(letters.substring(i,i+1),new Rect(x-1,first.centerY()-1,x+1,first.centerY()+1));
        }
    }

    private AccessibilityNodeInfo currentCard(String text, String meaning) {
        if (keyboard==null) lookupKeyboard("隐藏键盘");
        if (keyboard==null) return null;
        List<AccessibilityNodeInfo> nodes=keyboard.findAccessibilityNodeInfosByText(text);
        try {
            for (AccessibilityNodeInfo node:nodes) {
                if (!node.refresh() || !node.isEnabled() || !node.isVisibleToUser()) continue;
                CharSequence value=node.getContentDescription();
                if (value!=null && matchesChoice(value.toString(),text,meaning))
                    return AccessibilityNodeInfo.obtain(node);
            }
            return null;
        } finally {
            for (AccessibilityNodeInfo node:nodes) node.recycle();
        }
    }

    private Rect liveCard(String text,String meaning) {
        AccessibilityNodeInfo node=currentCard(text,meaning);
        if (node==null) return null;
        try {
            Rect r=new Rect();
            node.getBoundsInScreen(r);
            return r.isEmpty() ? null : r;
        } finally { node.recycle(); }
    }

    private Rect awaitCard(String text,String meaning,long timeout) {
        long until=SystemClock.uptimeMillis()+timeout;
        do {
            Rect r=liveCard(text,meaning);
            if (r!=null) return r;
            SystemClock.sleep(55);
        } while (SystemClock.uptimeMillis()<until);
        return null;
    }

    private void chooseRanked(String raw,String text,String meaning,int rank) {
        String label=choiceDescription(text,meaning);
        int page=(rank-1)/4;
        if (page>0) {
            verifyTyped(raw);
            for (int i=0;i<page;i++) press("下一页候选",25);
        }
        Rect seen=awaitCard(text,meaning,3000);
        if (seen==null && page==0) {
            verifyTyped(raw);
            seen=awaitCard(text,meaning,4000);
        }
        if (seen==null) throw new AssertionError("Missing live candidate "+label+" at rank "+rank+"; "+shown());
        int slot=(rank-1)%4;
        Rect tile=candidateTiles.get(slot);
        if (tile==null || !seen.contains(tile.centerX(),tile.centerY())) {
            tile=seen;
            candidateTiles.put(slot,tile);
        }
        moveCaretToEnd();
        // Use the actual visible card. When Android drops a coordinate tap,
        // activate that same live button only while both note and preedit are intact.
        tap(tile,16);
        String before=expected.toString();
        long deadline=SystemClock.uptimeMillis()+900;
        while (before.equals(editorText()) && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(35);
        if (before.equals(editorText()) && raw.equals(draft())) {
            moveCaretToEnd();
            AccessibilityNodeInfo card=currentCard(text,meaning);
            if (card!=null) {
                try { card.performAction(AccessibilityNodeInfo.ACTION_CLICK); }
                finally { card.recycle(); }
            }
        }
    }

    private String choiceDescription(String text, String meaning) {
        if ((meaning.equals("@written") || meaning.startsWith("@written:")) || meaning.equals(text)) return text;
        return (meaning.isEmpty() ? "汉语" : meaning) + "，" + text;
    }

    private boolean matchesChoice(String description,String text,String meaning) {
        if (description.equals(choiceDescription(text,meaning))) return true;
        String suffix="，"+text;
        if (meaning.isEmpty() || !description.endsWith(suffix)) return false;
        String gloss=description.substring(0,description.length()-suffix.length());
        String recorded=meaning.startsWith("@written:") ? meaning.substring("@written:".length())
                : meaning.equals("@written") ? text : meaning;
        // A merged form exposes all meanings. Compare whole sense words,
        // so e.g. 哥哥 cannot select a card whose only meaning is 哥哥啊.
        String separators="\\s*[、，,；;／/]\\s*";
        List<String> offered=Arrays.asList(gloss.split(separators));
        List<String> intended=Arrays.asList(recorded.split(separators));
        return offered.containsAll(intended);
    }

    private void choose(String text, String meaning, boolean slow) {
        String description=choiceDescription(text,meaning);
        for (int page=0; page<125; page++) {
            Rect card=awaitCard(text,meaning,page==0 ? 2600 : 900);
            if (card!=null) {
                if (slow) SystemClock.sleep(readingPace ? 2050 : 650);
                tap(card,readingPace ? 230 : 20);
                return;
            }
            Rect next=lookupKeyboard("下一页候选");
            if (next==null) throw new AssertionError("Missing candidate " + description + ". Visible: " + shown());
            tap(next,22);
            SystemClock.sleep(100);
        }
        throw new AssertionError("Too many pages: " + description);
    }

    private void appendPunctuation(String tail) {
        for (int i=0;i<tail.length();i++) {
            char ch=tail.charAt(i);
            String key;
            switch(ch) {
                case '，': key="逗号"; break;
                case '。': key="句号"; break;
                case '\n': key="换行"; break;
                case ' ': key="空格"; break;
                default: key=String.valueOf(ch);
            }
            boolean symbols="：；？！".indexOf(ch)>=0;
            if (symbols) press("字母或符号",25);
            press(key,readingPace ? 180 : 26);
            if (symbols) {
                press("字母或符号",25);
                require("q");
                afterSymbolPage=true;
            }
            expected.append(ch);
        }
    }

    private void press(String label,long duration) {
        if ("汉字记音".equals(label) || "土家语拼写".equals(label)) {
            label = "汉字记音".equals(label) ? HAN_BUTTON : spellingButton;
            require("隐藏键盘");
            if (lookupKeyboard(label) == null && lookupKeyboard("设置") != null) {
                // The settings menu follows the current input mode.
                // Return to typing before choosing the keyboard's output script.
                if (lookupKeyboard("q") == null) {
                    tap(require("打字"),100);
                    require("q");
                }
                tap(require("设置"),100);
                require(label);
                shot("mode-options");
            }
        }
        tap(require(label),duration);
    }
    private Rect require(String value) {
        long end=SystemClock.uptimeMillis() + 16000;
        do {
            Rect r=lookupKeyboard(value);
            if (r!=null) return r;
            SystemClock.sleep(65);
        } while (SystemClock.uptimeMillis()<end);
        throw new AssertionError("Missing IME control " + value + "; available " + shown());
    }

    private Rect lookupKeyboard(String label) {
        if (keyboard == null) {
            for (AccessibilityWindowInfo w : ui.getWindows()) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                AccessibilityNodeInfo r=w.getRoot();
                if (r!=null && IME.equals(r.getPackageName())) { keyboard=r; break; }
                if (r!=null) r.recycle();
            }
        }
        if (keyboard==null) return null;
        Rect match=matching(keyboard,label);
        if (match==null && !keyboard.refresh()) { keyboard.recycle(); keyboard=null; }
        return match;
    }

    private Rect appLabel(String label) {
        long end=SystemClock.uptimeMillis()+12000;
        do {
            AccessibilityNodeInfo r=ui.getRootInActiveWindow();
            if (r!=null) {
                try {
                    Rect pos=matching(r,label);
                    if (pos!=null) return pos;
                } finally {r.recycle();}
            }
            SystemClock.sleep(65);
        } while (SystemClock.uptimeMillis()<end);
        throw new AssertionError("Missing note control " + label);
    }

    private Rect matching(AccessibilityNodeInfo root, String label) {
        for (AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(label)) {
            try {
                if (!node.refresh() || !node.isVisibleToUser() || !node.isEnabled()) continue;
                CharSequence description=node.getContentDescription(), text=node.getText();
                if ((description==null || !label.contentEquals(description)) && (text==null || !label.contentEquals(text))) continue;
                Rect r=new Rect(); node.getBoundsInScreen(r);
                if (!r.isEmpty()) return r;
            } finally { node.recycle(); }
        }
        return null;
    }

    private String shown() {
        if (keyboard==null) return "no IME window";
        StringBuilder result=new StringBuilder();
        List<AccessibilityNodeInfo> cards=keyboard.findAccessibilityNodeInfosByText("，");
        for (AccessibilityNodeInfo n:cards) {
            try { result.append(' ').append(n.getContentDescription()); } finally {n.recycle();}
        }
        return result.toString();
    }

    private void tap(Rect r,long hold) {
        float x=r.centerX(),y=r.centerY();
        long down=SystemClock.uptimeMillis();
        if (readingPace) injectStroke(down,MotionEvent.ACTION_DOWN,x,y);
        else inject(down,MotionEvent.ACTION_DOWN,x,y);
        SystemClock.sleep(hold);
        android.graphics.Bitmap frame=captureStates ? ui.takeScreenshot() : null;
        inject(down,MotionEvent.ACTION_UP,x,y);
        if (captureStates) saveShot("press",frame);
    }

    private void inject(long down,int action,float x,float y) {
        MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            if (!ui.injectInputEvent(e,action==MotionEvent.ACTION_UP)) throw new AssertionError("Touch rejected");
        } finally { e.recycle(); }
    }

    private void waitForEditor() {
        long until=SystemClock.uptimeMillis()+9000;
        boolean[] ready={false};
        while (!ready[0] && SystemClock.uptimeMillis()<until) {
            runOnMainSync(() -> ready[0]=host.editor!=null && host.editor.getWidth()>0);
            SystemClock.sleep(30);
        }
        if (!ready[0]) throw new AssertionError("New note did not open");
    }

    private void expectEditor(String text) {
        long end=SystemClock.uptimeMillis()+9000;
        String[] actual=new String[1];
        do {
            runOnMainSync(() -> actual[0]=host.editor==null ? null : host.editor.getText().toString());
            if (text.equals(actual[0])) return;
            SystemClock.sleep(35);
        } while (SystemClock.uptimeMillis()<end);
        throw new AssertionError("Expected [" + text + "], got [" + actual[0] + "]");
    }
}
