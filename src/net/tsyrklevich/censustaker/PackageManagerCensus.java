package net.tsyrklevich.censustaker;

import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PathPermission;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.os.Build;
import android.os.PatternMatcher;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PackageManagerCensus {
  private static final Gson gson = new Gson();

  private static void pollSharedLibraries(PackageManager pm, Map<String, JsonElement> results) {
    String[] sharedLibraries = pm.getSystemSharedLibraryNames();
    results.put("system_shared_libraries", gson.toJsonTree(sharedLibraries, sharedLibraries.getClass()));
  }

  private static void pollFeatures(PackageManager pm, Map<String, JsonElement> results) {
    List<String> features = new ArrayList<>();
    for(FeatureInfo feature : pm.getSystemAvailableFeatures()) {
      if (feature.name != null) {
        features.add(feature.name);
      }
    }
    results.put("features", gson.toJsonTree(features, features.getClass()));
  }

  private static void pollPermissions(PackageManager pm, Map<String, JsonElement> results) {
    List<PermissionInfo> allPermissions =  new ArrayList<>();

    // Get permissions from packages
    // TODO: Do we want these??
    for (PackageInfo pi : pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
      if (pi.permissions != null) {
        allPermissions.addAll(Arrays.asList(pi.permissions));
      }
    }

    // Get permissions from permission groups
    // TODO: ungrouped permissions? Check against pm list permissions -g
    for (PermissionGroupInfo pgi : pm.getAllPermissionGroups(PackageManager.GET_META_DATA)) {
      try {
        allPermissions.addAll(pm.queryPermissionsByGroup(pgi.name, 0));
      } catch (PackageManager.NameNotFoundException e) {
        e.printStackTrace();
      }
    }

    Set<JsonElement> permissions = new HashSet<>();
    for (PermissionInfo perm : allPermissions) {
      Map<String, String> permission = new HashMap<>();
      permission.put("packageName", perm.packageName);
      permission.put("name", perm.name);
      permission.put("protectionLevel", String.format("%d", perm.protectionLevel));
      if (Build.VERSION.SDK_INT >= 17) {
        permission.put("flags", String.format("%d", perm.flags));
      }

      permissions.add(gson.toJsonTree(permission, permission.getClass()));
    }
    results.put("permissions", gson.toJsonTree(permissions));
  }

  private static void pollContentProviders(PackageManager pm, Map<String, JsonElement> results) {
    List<ProviderInfo> providerInfos = pm.queryContentProviders(null, 0, 0);
    if (providerInfos == null) {
      return;
    }

    List<JsonElement> providers = new ArrayList<>();
    for (ProviderInfo provider : providerInfos) {
      Map<String, JsonElement> providerData = new HashMap<>();
      providerData.put("authority",
          gson.toJsonTree(provider.authority, provider.authority.getClass()));
      providerData.put("multiprocess", gson.toJsonTree(provider.multiprocess, boolean.class));
      providerData.put("grantUriPermissions",
          gson.toJsonTree(provider.grantUriPermissions, boolean.class));
      providerData.put("initOrder", gson.toJsonTree(provider.initOrder, int.class));

      if (provider.readPermission != null) {
        providerData.put("readPermission",
            gson.toJsonTree(provider.readPermission, provider.readPermission.getClass()));
      }
      if (provider.writePermission != null) {
        providerData.put("writePermission",
            gson.toJsonTree(provider.writePermission, provider.writePermission.getClass()));
      }
      if (Build.VERSION.SDK_INT >= 17) {
        providerData.put("flags", gson.toJsonTree(provider.flags, int.class));
      }
      if(provider.pathPermissions != null) {
        List<String> pathPermissions = new ArrayList<>();
        for (PathPermission pp : provider.pathPermissions) {
          pathPermissions.add(String.format("%s,%s,%s", pp.toString(), pp.getReadPermission(),
              pp.getWritePermission()));
        }

        providerData.put("pathPermissions", gson.toJsonTree(gson.toJson(pathPermissions)));
      }
      if (provider.uriPermissionPatterns	!= null) {
        List<String> uriPermissionPatterns = new ArrayList<>();
        for (PatternMatcher pattern : provider.uriPermissionPatterns) {
          uriPermissionPatterns.add(pattern.toString());
        }

        providerData.put("uriPermissionPatterns", gson.toJsonTree(gson.toJson(uriPermissionPatterns)));
      }

      providers.add(gson.toJsonTree(providerData, providerData.getClass()));
    }

    results.put("providers", gson.toJsonTree(providers, providers.getClass()));
  }

  public static Map<String, JsonElement> poll(PackageManager pm) {
    Map<String, JsonElement> results = new HashMap<>();

    pollSharedLibraries(pm, results);
    pollFeatures(pm, results);
    pollPermissions(pm, results);
    pollContentProviders(pm, results);

    return results;
  }
}
