package net.tsyrklevich.censustaker;

import com.esotericsoftware.wildcard.Paths;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.filefilter.RegexFileFilter;

public class FileSystemCensus {
  private static final Gson gson = new Gson();

  /**
   * Implemented natively because Java doesn't allow us to get POSIX file permissions. Sigh.
   */
  private static native ArrayList<FileInformation> scanDirRecursive(String dir, int depth);

  private class FileInformation {
    public String path; // @NotNull
    public String linkPath; // @Nullable
    public int uid;
    public int gid;
    public int size;
    public int mode;
    public String selinuxContext; // @Nullable

    public FileInformation(byte[] path, byte[] linkPath, int uid, int gid, int size, int mode, byte[] selinuxContext) {
      this.path = new String(path);
      if (linkPath != null) {
        this.linkPath = new String(linkPath);
      }
      this.uid = uid;
      this.gid = gid;
      this.size = size;
      this.mode = mode;
      if (selinuxContext != null) {
        this.selinuxContext = new String(selinuxContext);
      }
    }
  }

  private static void pollPermissions(Map<String, JsonElement> results) {
    System.loadLibrary("censustaker");

    ArrayList<FileInformation> filePerms = new ArrayList<>();
    filePerms.addAll(scanDirRecursive("/", 1));
    filePerms.addAll(scanDirRecursive("/data/system", 1000));
    filePerms.addAll(scanDirRecursive("/dev", 1000));
    filePerms.addAll(scanDirRecursive("/etc", 1000));
    filePerms.addAll(scanDirRecursive("/sbin", 1000));
    filePerms.addAll(scanDirRecursive("/system", 1000));
    filePerms.addAll(scanDirRecursive("/vendor", 1000));

    /*
     * /proc and /sys are really big, so we cherry pick things that we think
     *  might be interesting
     */
    filePerms.addAll(scanDirRecursive("/proc", 1));
    filePerms.addAll(scanDirRecursive("/proc/bus", 1000));
    filePerms.addAll(scanDirRecursive("/proc/cpu", 1000));
    filePerms.addAll(scanDirRecursive("/proc/tty", 1000));
    filePerms.addAll(scanDirRecursive("/sys", 2));
    filePerms.addAll(scanDirRecursive("/sys/fs/selinux", 1000));

    results.put("file_permissions", gson.toJsonTree(filePerms.toArray(),
        filePerms.toArray().getClass()));
  }

  /**
   * @return List of paths for file whose contents we wish to upload
   */
  private static List<String> interestingFiles() {
    ArrayList<String> files = new ArrayList<String>(Arrays.asList(
        "/default.prop",
        "/property_contexts",
        "/seapp_contexts",
        "/sepolicy",
        "/data/local.prop",
        "/factory/factory.prop",
        "/proc/cmdline",
        "/proc/config.gz",
        "/proc/consoles",
        "/proc/cpuinfo",
        "/proc/devices",
        "/proc/fb",
        "/proc/filesystems",
        "/proc/iomem",
        "/proc/meminfo",
        "/proc/misc",
        "/proc/modules",
        "/proc/mounts",
        "/proc/pagetypeinfo",
        "/proc/slabinfo",
        "/proc/version",
        "/proc/vmallocinfo",
        "/proc/vmstat",
        "/proc/zoneinfo",
        "/proc/bus/input/devices",
        "/proc/net/unix",
        "/proc/self/environ",
        "/proc/self/maps",
        "/proc/tty/drivers",
        "/system/build.prop",
        "/system/default.prop",
        "/system/build.prop"
     /*
      * /proc/driver ?
      * /proc/tty/**
      * /sys/module/* (one deep is sort of interesting) as could /sys/module/* /parameters
     */
    ));

    Paths paths = new Paths();
    paths.glob("/", "init*.rc");
    paths.glob("/", "ueventd*.rc");
    paths.glob("/system/etc/permissions", "*.xml");
    paths.glob("/sys/fs/selinux", "**" , "!class/**");
    paths.glob("/sys/fs/module", "**/version");
    files.addAll(paths.getPaths());

    // We have to do these manually because /proc is wacky.
    String[] procDirs = new File("/proc").list(new RegexFileFilter("[0-9]+"));
    for (String procDir : procDirs) {
      files.add(String.format("/proc/%s/cmdline", procDir));
      files.add(String.format("/proc/%s/status", procDir));
      files.add(String.format("/proc/%s/attr/current", procDir));
      files.add(String.format("/proc/%s/attr/fscreate", procDir));
    }

    return files;
  }

  /**
   * Unfortunately Java/Apache-commons various APIs to read a whole file don't
   *  work well with /proc because stat() returns st_size=0, so I wrote my own.
   */
  static private String readFileToBase64(String path) throws IOException {
    ByteArrayOutputStream contents = new ByteArrayOutputStream();
    byte chunk[] = new byte[1024];

    RandomAccessFile f = new RandomAccessFile(path, "r");
    int len = 0;
    while (true) {
      len = f.read(chunk);
      if (len == -1) {
        break;
      }
      contents.write(chunk, 0, len);
    }

    return new String(Base64.encodeBase64(contents.toByteArray()));
  }

  private static void pollSmallFileContents(Map<String, JsonElement> results) {
    Map<String, String> fileContents = new HashMap<>();
    for (String path : interestingFiles()) {
      try {
        fileContents.put(path, readFileToBase64(path));
      } catch (IOException e) {
      }
    }

    results.put("small_files", gson.toJsonTree(fileContents,
        fileContents.getClass()));
  }

  public static Map<String, JsonElement> poll() {
    Map<String, JsonElement> results = new HashMap<>();
    pollPermissions(results);
    pollSmallFileContents(results);

    return results;
  }
}