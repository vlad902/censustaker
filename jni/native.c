#include <stdio.h>
#include <string.h>
#include <jni.h>
#include <dirent.h>
#include <sys/stat.h>
#include <limits.h>
#include <unistd.h>
#include <stdbool.h>
#include <stdlib.h>

#include "selinux.h"
#include "log.h"

// We cache these because JNI calls are slow and we perform a lot of them.
static jclass arrayListClass;
static jmethodID arrayListInit;
static jmethodID arrayListAdd;
static jclass fileInformationClass;
static jmethodID fileInformationInit;

static bool resolveJNIFunctions(JNIEnv *env)
{
    arrayListClass = (*env)->FindClass(env, "java/util/ArrayList");
    arrayListClass = (*env)->NewGlobalRef(env, arrayListClass);
    if (arrayListClass == NULL) {
        err("Failed to find ArrayList");
        return false;
    }

    arrayListInit = (*env)->GetMethodID(env, arrayListClass, "<init>", "()V");
    if (arrayListInit == NULL) {
        err("Failed to find ArrayList#init");
        return false;
    }

    arrayListAdd = (*env)->GetMethodID(env, arrayListClass, "add", "(Ljava/lang/Object;)Z");
    if (arrayListAdd == NULL) {
        err("Failed to find ArrayList#add");
        return false;
    }

    fileInformationClass = (*env)->FindClass(env, "net/tsyrklevich/censustaker/FileSystemCensus$FileInformation");
    fileInformationClass = (*env)->NewGlobalRef(env, fileInformationClass);
    if (fileInformationClass == NULL) {
        err("Failed to resolve FileInformation");
        return false;
    }

    fileInformationInit = (*env)->GetMethodID(env, fileInformationClass, "<init>", "(Lnet/tsyrklevich/censustaker/FileSystemCensus;[B[BIIII[B)V");
    if (fileInformationInit == NULL) {
        err("Failed to find FileInformation#init");
        return false;
    }

    return true;
}

static jobject initializeArrayList(JNIEnv *env)
{
    jobject array = (*env)->NewObject(env, arrayListClass, arrayListInit);
    if (array == NULL) {
        err("Failed to create new ArrayList");
        return NULL;
    }

    return array;
}

static void addToArrayList(JNIEnv *env, jobject *array, jobject *object)
{
    jboolean ret = (*env)->CallBooleanMethod(env, array, arrayListAdd, object);
    if (!ret) {
        err("ArrayList#add failed");
        return;
    }
}

static jobject createFileInformation(JNIEnv *env, const char *path, const char *linkpath,
        int uid, int gid, int size, int mode, char *selinuxcontext)
{
    jbyteArray jpath = (*env)->NewByteArray(env, strlen(path));
    if (jpath == NULL) {
        err("NewByteArray failed for path");
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, jpath, 0, strlen(path), (jbyte*)path);

    jbyteArray jlinkpath = NULL;
    if (linkpath) {
        jlinkpath = (*env)->NewByteArray(env, strlen(linkpath));
        if (jlinkpath == NULL) {
            err("NewByteArray failed for linkpath");
            return NULL;
        }
        (*env)->SetByteArrayRegion(env, jlinkpath, 0, strlen(linkpath), (jbyte*)linkpath);
    }

    jbyteArray jselinuxcontext = NULL;
    if (selinuxcontext) {
        jselinuxcontext = (*env)->NewByteArray(env, strlen(selinuxcontext));
        if (jselinuxcontext == NULL) {
            err("NewByteArray failed for selinuxcontext");
            return NULL;
        }
        (*env)->SetByteArrayRegion(env, jselinuxcontext, 0, strlen(selinuxcontext), (jbyte*)selinuxcontext);
    }

    jobject fi = (*env)->NewObject(env, fileInformationClass, fileInformationInit, NULL, jpath,
            jlinkpath, (jint)uid, (jint)gid, (jint)size, (jint)mode, jselinuxcontext);

    (*env)->DeleteLocalRef(env, jpath);
    if (jlinkpath) {
        (*env)->DeleteLocalRef(env, jlinkpath);
    }
    if (jselinuxcontext) {
        (*env)->DeleteLocalRef(env, jselinuxcontext);
    }

    if (fi == NULL) {
        err("Failed to create FileInformation");
        return NULL;
    }

    return fi;
}

static void scanDirRecursive(JNIEnv * env, jobject array, const char *dir, int depth)
{
    DIR *dirp = opendir(dir);
    if (!dirp) {
        err("opendir failed");
        return;
    }

    char path[PATH_MAX + 1];
    char _linkpath[PATH_MAX + 1], *linkpath;
    ssize_t linkpath_size;
    struct stat st;
    struct dirent *de;
    while ((de = readdir(dirp)) != NULL) {
        if (!strcmp(de->d_name, ".") || !strcmp(de->d_name, "..")) {
            continue;
        }

        if (!strcmp(dir, "/")) {
            snprintf(path, sizeof(path), "/%s", de->d_name);
        } else {
            snprintf(path, sizeof(path), "%s/%s", dir, de->d_name);
        }

        if (lstat(path, &st) < 0) {
            LOGE("Failed to lstat %s", path);
            continue;
        }

        if ((st.st_mode & S_IFMT) == S_IFLNK) {
            if ((linkpath_size = readlink(path, _linkpath, sizeof(_linkpath))) <= 0) {
                err("readlink failed");
                strcpy(_linkpath, "error");
            } else {
                _linkpath[linkpath_size] = 0;
            }
            linkpath = _linkpath;
        } else {
            linkpath = NULL;
        }

        char *selinuxcontext = NULL;
        lgetfilecon(path, &selinuxcontext);

#if 1
        LOGI("path=%s linkpath=%s uid=%lu gid=%lu size=%llu mode=%o selinuxcontext=%s",
                path,
                linkpath,
                st.st_uid,
                st.st_gid,
                st.st_size,
                st.st_mode,
                selinuxcontext);
#endif

        jobject object = createFileInformation(env, path, linkpath, st.st_uid, st.st_gid,
                st.st_size, st.st_mode, selinuxcontext);

        if (selinuxcontext) {
            free(selinuxcontext);
        }

        if (object != NULL) {
            addToArrayList(env, array, object);
            (*env)->DeleteLocalRef(env, object);
        }

        if ((st.st_mode & S_IFMT) == S_IFDIR && depth > 1) {
            scanDirRecursive(env, array, path, depth - 1);
        }
    }
    
    closedir(dirp);
}

JNIEXPORT jobject JNICALL Java_net_tsyrklevich_censustaker_FileSystemCensus_scanDirRecursive(JNIEnv * env, jclass clazz, jstring jdir, jint depth)
{
    // Lame, pthread_once can't pass a JNIEnv parameter
    static bool jniInitialized = false;
    if (!jniInitialized) {
        if (!resolveJNIFunctions(env)) {
            err("resolveJNIFunctions() failed");
            return NULL;
        }

        resolveSELinuxFunctions();
        jniInitialized = true;
    }

    jobject array = initializeArrayList(env);
    if (array == NULL) {
        err("Failed to initialize ArrayList");
        return NULL;
    }

    const char *dir = (*env)->GetStringUTFChars(env, jdir, 0);
    scanDirRecursive(env, array, dir, depth);
    (*env)->ReleaseStringUTFChars(env, jdir, dir);

    return array;
}
