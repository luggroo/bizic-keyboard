package biz.tujia.ime;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;

/** Runtime permission must be requested from an Activity, never from the IME service. */
public final class VoicePermissionActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) return;
        new AlertDialog.Builder(this).setTitle("北部语音输入")
                .setMessage("使用时，录音会发送到 TujiaNLP 的在线识别服务（tujianlp-tujia-asr-demo.ms.show）。识别后可选择原始音标或本机词库候选。请只在愿意发送录音时使用。")
                .setNegativeButton("取消", (d, w) -> finish())
                .setPositiveButton("允许使用麦克风", (d, w) -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1))
                .setOnCancelListener(d -> finish()).show();
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        finish();
    }
}
