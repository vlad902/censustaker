package net.tsyrklevich.censustaker;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.DefaultHttpClient;

public class MainActivity extends Activity {
  private Map<String, JsonElement> results = new HashMap<>();
  private final Gson gson = new Gson();

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
   * @param data
   */
  private void logLongData(String data) {
    final int SPLIT_LEN = 950;
    for (int index = 0; index < data.length(); index += SPLIT_LEN) {
      Log.e("longdata", data.substring(index, Math.min(index + SPLIT_LEN, data.length())));
    }
  }

  private void writeResultsToDisk(String jsonResults) {
    File outputDir = Environment.getExternalStorageDirectory();
    try {
      File outputFile = File.createTempFile("device_data", ".json", outputDir);
      FileOutputStream stream = new FileOutputStream(outputFile);
      stream.write(jsonResults.getBytes("UTF8"));
      stream.flush();
      stream.close();

      // We can't use setReadable on old devices.
      Runtime.getRuntime().exec("chmod 777 " + outputFile.getAbsolutePath());
      //outputFile.setReadable(true, false);

      Log.i("censustaker", outputFile.getAbsolutePath());
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private boolean postResults(String hostname, byte[] jsonResults) {
    HttpClient http = new DefaultHttpClient();
    HttpPost post = new HttpPost("http://" + hostname + "/results/new");

    /* Prevent random posts in production, we don't care in development. */
    try {
      String password = IOUtils.toString(getAssets().open("server_password"), "UTF8");
      post.setHeader("Authorization", password.replace("\n", ""));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    post.setHeader("Content-Type", "application/octet-stream");
    post.setEntity(new ByteArrayEntity(jsonResults));

    Log.i("censustaker", "Sending up data!");
    HttpResponse response;
    try {
      response = http.execute(post);
    } catch (IOException e) {
      e.printStackTrace();
      return false;
    }

    if (response.getStatusLine().getStatusCode() != 200) {
      Log.e("censustaker", "/results/new failed with response " + response.getStatusLine().toString());
      return false;
    }

    Log.i("censutaker", "Sucessfully uploaded to server!");
    return true;
  }

  public void postCensus() {
    try {
      byte[] jsonResults = gson.toJson(results).getBytes("UTF8");

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
            } catch(InterruptedException ignored) {
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

  /** Called when the activity is first created. */
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.main);

    try {
      String aws_access_key = IOUtils.toString(getAssets().open("aws_access_key"), "UTF8").replace("\n", "");
      String aws_secret_key = IOUtils.toString(getAssets().open("aws_secret_key"), "UTF8").replace("\n", "");
      String s3_bucket_name = IOUtils.toString(getAssets().open("s3_bucket_name"), "UTF8").replace("\n", "");
      String s3_path_list = IOUtils.toString(getAssets().open("s3_path_list"), "UTF8");
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

    try {
      byte[] jsonResults = gson.toJson(results).getBytes("UTF8");
      writeResultsToDisk(new String(jsonResults));
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
