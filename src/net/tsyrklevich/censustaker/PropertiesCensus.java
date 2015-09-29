package net.tsyrklevich.censustaker;

import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.CanReadFileFilter;
import org.apache.commons.io.filefilter.DirectoryFileFilter;

public class PropertiesCensus {
  private static final Gson gson = new Gson();

  private static void pollEnvironmentVariables(Map<String, JsonElement> results) {
    Map<String, String> env_vars = System.getenv();
    results.put("environment_variables", gson.toJsonTree(env_vars, env_vars.getClass()));
  }

  private static void pollSystemProperties(Map<String, JsonElement> results) {
    Pattern pattern = Pattern.compile("^\\[(.+)\\]: \\[(.+)\\]$");
    Map<String, String> properties = new HashMap<>();

    try {
      Process proc = Runtime.getRuntime().exec("getprop");
      BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(proc.getInputStream()));

      String line;
      while ((line = bufferedReader.readLine()) != null) {
        Matcher matcher = pattern.matcher(line);
        while (matcher.find()) {
          properties.put(matcher.group(1), matcher.group(2));
        }
      }
    } catch(IOException e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }

    results.put("system_properties", gson.toJsonTree(properties, properties.getClass()));
  }

  private static void pollSysctl(Map<String, JsonElement> results) {
    Map<String, String> sysctls = new HashMap<>();

    Collection<File> files = FileUtils.listFiles(new File("/proc/sys"), CanReadFileFilter.CAN_READ, DirectoryFileFilter.DIRECTORY);
    for (File file : files) {
      String sysctl = file.getAbsolutePath().replaceAll("^/proc/sys/", "").replaceAll("/", ".");
      try {
        String contents = FileUtils.readFileToString(file, "UTF8");
        contents = contents.replaceAll("\n$", "");
        sysctls.put(sysctl, contents);
      } catch (IOException e) {
        Log.e("censustaker", "Failed to read " + file + ": " + e.toString());
        e.printStackTrace();
      }
    }

    results.put("sysctl", gson.toJsonTree(sysctls, sysctls.getClass()));
  }

  public static Map<String, JsonElement> poll() {
    Map<String, JsonElement> results = new HashMap<>();
    pollSystemProperties(results);
    pollSysctl(results);
    pollEnvironmentVariables(results);

    return results;
  }
}
