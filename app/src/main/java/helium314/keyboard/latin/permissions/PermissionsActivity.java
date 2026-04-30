package helium314.keyboard.latin.permissions;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class PermissionsActivity extends Activity {
    private static final int PERMISSION_REQUEST_CODE = 123;
    private static final String EXTRA_PERMISSION = "extra_permission";

    public static void run(Context context, String permission) {
        Intent intent = new Intent(context, PermissionsActivity.class);
        intent.putExtra(EXTRA_PERMISSION, permission);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Transparent activity

        String permission = getIntent().getStringExtra(EXTRA_PERMISSION);
        if (permission == null) {
            finish();
            return;
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            finish();
            return;
        }

        ActivityCompat.requestPermissions(this, new String[] { permission }, PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // We could broadcast result or just finish.
        // Logic in LatinIME will check permission again on next click.
        finish();
    }
}
