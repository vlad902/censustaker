package net.tsyrklevich.censustaker;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;

public class MainActivity extends Activity {
    private Map<String, JsonElement> results = new HashMap<>();
    private final Gson gson = new Gson();
    private Button storage;
    private static final int STORAGE_PERMISSION_CODE = 101;
    public boolean uploadedRequest = false;

    public void getDeviceInfo() {
        Map<String, String> deviceInfo = new HashMap<>();

        String deviceName;
        String manufacturer = Build.MANUFACTURER;
        String model = Build.MODEL;
        String version = Build.VERSION.RELEASE;
        if (model.toLowerCase().startsWith(manufacturer.toLowerCase())) {
            deviceName = String.format("%s (%s)", model, version);
        } else {
            deviceName = String.format("%s %s (%s)", manufacturer, model, version);
        }

        results.put("device_name", gson.toJsonTree(deviceName, deviceName.getClass()));
    }

    /**
     * Split-up long strings into chunks so logcat doesn't truncate them.
     *
     * @param data the data to split
     */
    private void logLongData(String data) {
        final int SPLIT_LEN = 950;
        for (int index = 0; index < data.length(); index += SPLIT_LEN) {
            Log.e("longdata", data.substring(index, Math.min(index + SPLIT_LEN, data.length())));
        }
    }

    private String writeResultsToDisk(String jsonResults) {
        File outputDir = Environment.getExternalStorageDirectory();
        try {
            File outputFile = File.createTempFile("device_data", ".json", outputDir);
            FileOutputStream stream = new FileOutputStream(outputFile);
            stream.write(jsonResults.getBytes(StandardCharsets.UTF_8));
            stream.flush();
            stream.close();

            // We can't use setReadable on old devices.
            Runtime.getRuntime().exec("chmod 777 " + outputFile.getAbsolutePath());
            //outputFile.setReadable(true, false);

            Log.i("censustaker", outputFile.getAbsolutePath());
            return outputFile.getAbsolutePath();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return jsonResults;
    }

    private boolean postResults(String hostname, byte[] jsonResults) {
        URL url = null;
        HttpURLConnection conn = null;
        try {
            url = new URL("http://" + hostname + "/results/new");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            /* Prevent random posts in production, we don't care in development. */
            try {
                String password = IOUtils.toString(getAssets().open("server_password"), StandardCharsets.UTF_8);
                conn.setRequestProperty("Authorization", password.replace("\n", ""));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            Log.i("censustaker", "Sending up data!");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonResults, 0, jsonResults.length);
            }
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.e("censustaker", "/results/new failed with response " + conn.getResponseMessage());
                return false;
            }

        } catch (MalformedURLException e) {
            Log.e("censustaker", " Error creating http://" + hostname + "/results/new");
            return false;
        } catch (IOException e) {
            Log.e("censustaker", " Error connecting http://" + hostname + "/results/new");
            return false;
        }
        Log.i("censutaker", "Successfully uploaded to server!");
        return true;

    }

    public void postCensus() {
        try {
            byte[] jsonResults = gson.toJson(results).getBytes(StandardCharsets.UTF_8);

            ByteArrayOutputStream bout = new ByteArrayOutputStream(jsonResults.length);
            DeflaterOutputStream out = new DeflaterOutputStream(bout);
            out.write(jsonResults);
            out.close();

            final byte[] compressed = bout.toByteArray();

            Thread t = new Thread() {
                public void run() {
                    for (int retries = 4; retries >= 0; retries--) {
                        // Reminder to self: census.tsyrklevi.ch is configured without SSL so
                        //  that old Android devices without SNI can hit it. Switching to
                        //  census.tsyrklevich.net will require some additional code for
                        //  cert validation and potentially loss of support for old clients.
                        if (postResults("census.tsyrklevi.ch", compressed)) {
                            uploadedRequest = true;
                            break;
                        }

                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            };

            t.start();
            t.join();

            // Dump to logcat if we couldn't upload
            if (!uploadedRequest) {
                logLongData(new String(Base64.encodeBase64(compressed)));
            }
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        } catch (InterruptedException ignored) {
        }
    }

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        storage = findViewById(R.id.storage);
        // Set Buttons on Click Listeners
        storage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkPermission(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        STORAGE_PERMISSION_CODE);
            }
        });
        final TextView tv = (TextView) findViewById(R.id.textView);

        try {
            String aws_access_key = IOUtils.toString(getAssets().open("aws_access_key"), StandardCharsets.UTF_8).replace("\n", "");
            String aws_secret_key = IOUtils.toString(getAssets().open("aws_secret_key"), StandardCharsets.UTF_8).replace("\n", "");
            String s3_bucket_name = IOUtils.toString(getAssets().open("s3_bucket_name"), StandardCharsets.UTF_8).replace("\n", "");
            String s3_path_list = IOUtils.toString(getAssets().open("s3_path_list"), StandardCharsets.UTF_8);
            List<String> paths = Arrays.asList(s3_path_list.split("[\\n]+"));

            S3Uploader.upload(aws_access_key, aws_secret_key, s3_bucket_name, paths);
            uploadedRequest = true;
        } catch (IOException e) {
            e.printStackTrace();
        }

        getDeviceInfo();
        results.putAll(PropertiesCensus.poll());
        results.putAll(PackageManagerCensus.poll(getPackageManager()));
        results.putAll(FileSystemCensus.poll());

        byte[] jsonResults = gson.toJson(results).getBytes(StandardCharsets.UTF_8);
        String outputfile = writeResultsToDisk(new String(jsonResults));
        tv.append("Output file: " + outputfile);

        Gson gson = new Gson();
        try {
            Reader reader = Files.newBufferedReader(Paths.get(outputfile));
            Map<?, ?> map = gson.fromJson(reader, Map.class);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Log.i("censustaker", entry.getKey() + "=" + entry.getValue());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }


    }

    // Function to check and request permission
    public void checkPermission(String permission, int requestCode) {

        // Checking if permission is not granted
        if (ContextCompat.checkSelfPermission(MainActivity.this, permission)
                == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(MainActivity.this,
                    new String[]{permission},
                    requestCode);
        } else {
            Toast.makeText(MainActivity.this,
                    "Permission already granted",
                    Toast.LENGTH_SHORT)
                    .show();
        }
    }

    // This function is called when user accept or decline the permission.
    // Request Code is used to check which permission called this function.
    // This request code is provided when user is prompt for permission.

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super
                .onRequestPermissionsResult(requestCode,
                        permissions,
                        grantResults);

        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(MainActivity.this,
                        "Storage Permission Granted",
                        Toast.LENGTH_SHORT)
                        .show();
            } else {
                Toast.makeText(MainActivity.this,
                        "Storage Permission Denied",
                        Toast.LENGTH_SHORT)
                        .show();
            }
        }
    }
}
