package cn.sencs.llap;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private SeekBar sb;
    private TextView tv;
    private int distance;
    private Button btnStartRecording;
    private Button btnStopRecording;
    private boolean isRecording = false;

    static {
        System.loadLibrary("LLAP");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initPermission();
        setContentView(R.layout.activity_main);

        tv = (TextView) findViewById(R.id.textView);
        sb = (SeekBar)findViewById(R.id.seekBar);
        final Button bt = (Button)findViewById(R.id.button);
        btnStartRecording = (Button)findViewById(R.id.btnStartRecording);
        btnStopRecording = (Button)findViewById(R.id.btnStopRecording);
        
        tv.setText("0 mm");

        bt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Begin();
            }
        });
        
        // 开始录制按钮
        btnStartRecording.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startWavRecording();
            }
        });
        
        // 停止录制按钮
        btnStopRecording.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopWavRecording();
            }
        });
        
        // 初始状态：停止录制按钮禁用
        btnStopRecording.setEnabled(false);
    }

    public void refresh(int progress){
        distance = progress;
        // 直接在主线程更新UI（实时显示）
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tv.setText(Integer.toString(distance)+" mm");
                sb.setProgress(distance);
            }
        });
    }

    private void initPermission() {
        String permissions[] = {Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.INTERNET,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        };

        ArrayList<String> toApplyList = new ArrayList<String>();

        for (String perm :permissions){
            if (PackageManager.PERMISSION_GRANTED != ContextCompat.checkSelfPermission(this, perm)) {
                toApplyList.add(perm);
                //进入到这里代表没有权限.

            }
        }
        String tmpList[] = new String[toApplyList.size()];
        if (!toApplyList.isEmpty()){
            ActivityCompat.requestPermissions(this, toApplyList.toArray(tmpList), 123);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {

    }
    
    // ===== WAV录制功能 =====
    private void startWavRecording() {
        // 方案1：使用App私有目录（无需权限，推荐）
        File directory = new File(getExternalFilesDir(null), "recordings");
        
        // 方案2：使用外部存储（需要权限，Android 10+可能失败）
        // File directory = new File(Environment.getExternalStorageDirectory(), "LLAP");
        
        if (!directory.exists()) {
            boolean created = directory.mkdirs();
            if (!created) {
                Toast.makeText(this, "创建目录失败: " + directory.getAbsolutePath(), Toast.LENGTH_LONG).show();
                return;
            }
        }
        
        // 生成文件名（带时间戳）
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        String timestamp = sdf.format(new Date());
        String filename = directory.getAbsolutePath() + "/recording_" + timestamp + ".wav";
        
        Toast.makeText(this, "尝试保存到: " + filename, Toast.LENGTH_LONG).show();
        
        // 调用Native方法开始录制
        boolean success = startRecording(filename);
        
        if (success) {
            isRecording = true;
            btnStartRecording.setEnabled(false);
            btnStopRecording.setEnabled(true);
            Toast.makeText(this, "开始录制!\n文件: " + filename, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "录制失败！请查看logcat日志", Toast.LENGTH_LONG).show();
        }
    }
    
    private void stopWavRecording() {
        // 调用Native方法停止录制
        stopRecording();
        
        isRecording = false;
        btnStartRecording.setEnabled(true);
        btnStopRecording.setEnabled(false);
        
        File directory = new File(getExternalFilesDir(null), "recordings");
        Toast.makeText(this, "录制已停止\n文件位置: " + directory.getAbsolutePath(), Toast.LENGTH_LONG).show();
    }

    // ===== Native方法声明 =====
    public native void Begin();
    public native boolean startRecording(String filename);
    public native void stopRecording();
    public native boolean isRecording();
}
