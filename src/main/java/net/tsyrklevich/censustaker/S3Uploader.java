package net.tsyrklevich.censustaker;

import android.os.Build;
import android.util.Log;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.DeleteObjectRequest;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.google.gson.Gson;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class S3Uploader {
  private static int BLOCK_SIZE = 1024 * 1024;

  // Get around horrific commons-codec dependency hell for Hex.encodeHexString
  final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();
  public static String encodeHex(byte[] bytes) {
    char[] hexChars = new char[bytes.length * 2];
    for ( int j = 0; j < bytes.length; j++ ) {
      int v = bytes[j] & 0xFF;
      hexChars[j * 2] = hexArray[v >>> 4];
      hexChars[j * 2 + 1] = hexArray[v & 0x0F];
    }
    return new String(hexChars);
  }

  static private String hash(String path) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    FileInputStream inputStream = new FileInputStream(new File(path));
    DigestInputStream digestInputStream = new DigestInputStream(inputStream, digest);

    byte[] buffer = new byte[BLOCK_SIZE];
    while (digestInputStream.read(buffer) != -1);

    return encodeHex(digest.digest());
  }

  static private void uploadFile(AmazonS3 s3client, String bucket_name, String path) {

    String hash;
    boolean found = false;
    try {
      hash = hash(path);

      ListObjectsRequest listObjectsRequest = new ListObjectsRequest()
          .withBucketName(bucket_name)
          .withPrefix(hash);

      ObjectListing objectListing = s3client.listObjects(listObjectsRequest);
      if (objectListing.getObjectSummaries().size() != 0) {
        Log.e("s3", hash + " already exists");
        found = true;
      }
    } catch (Exception e) {
      e.printStackTrace();
      return;
    }

    Map<String, String> json = new HashMap<>();
    json.put("name", path);
    json.put("build_manufacturer", Build.MANUFACTURER);
    json.put("build_model", Build.MODEL);
    json.put("build_api", Integer.toString(Build.VERSION.SDK_INT));
    json.put("build_abi", Build.CPU_ABI);
    json.put("build_fingerprint", Build.FINGERPRINT);
    String jsonDescription = new Gson().toJson(json);

    // Don't reupload the same file
    if (!found) {
      Log.i("s3", "Uploading " + path);
      File file = new File(path);
      s3client.putObject(new PutObjectRequest(bucket_name, hash, file));
    }

    // But do re-upload the JSON because I've added fields over time.
    if (found) {
      // TODO: Just compare the JSON lengths from the list object requests instead of always deleting?
      // Going to ignore this logic until we do that.
      return;
      //s3client.deleteObject(new DeleteObjectRequest(bucket_name, hash + ".json"));
    }

    ObjectMetadata metadata = new ObjectMetadata();
    metadata.setContentLength(jsonDescription.length());
    InputStream stream = new ByteArrayInputStream(jsonDescription.getBytes());
    s3client.putObject(new PutObjectRequest(bucket_name, hash + ".json", stream, metadata));
  }

  static public void upload(String access_key, String secret_key, final String bucket_name, List<String> paths) {
    AWSCredentials awsCreds = new BasicAWSCredentials(access_key, secret_key);
    final AmazonS3 s3client = new AmazonS3Client(awsCreds);

    for (final String path : paths) {
      Log.i("s3", "Trying to upload " + path);

      Thread t = new Thread() {
        public void run() {
          uploadFile(s3client, bucket_name, path);
        }
      };

      try {
        t.start();
        t.join();
      } catch (InterruptedException ignored) {
      }
    }
  }
}
