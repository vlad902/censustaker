#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <errno.h>
#include <stdbool.h>
#include <dlfcn.h>

#include "selinux.h"
#include "log.h"

/*
 * bionic includes SELinux syscall handlers but they're not defined in headers or the bionic
 *  shipped with the NDK (so linking fails.)
 */
static ssize_t (*_lgetxattr)(const char *path, const char *name, void *value, size_t size);
void resolveSELinuxFunctions(void)
{
    void *handle = dlopen("libc.so", RTLD_GLOBAL | RTLD_LAZY);
    if (handle == NULL) {
        err("dlopen failed");
    }   

    _lgetxattr = dlsym(handle, "lgetxattr");
    dlclose(handle);
    if (_lgetxattr == NULL) {
        err("Failed to resolve lgetxattr");
    }   
}

/*
 * Taken from Android's source code repository (external/libselinux/src/lgetfilecon.c) and
 *  lightly modified to make free standing.
 *
 * libselinux is in the public domain.
 */
#define INITCONTEXTLEN 255
#define XATTR_NAME_SELINUX "security.selinux"
int lgetfilecon(const char *path, char** context)
{
    char *buf;
    ssize_t size;
    ssize_t ret;

    if (!_lgetxattr) {
        return -1;
    }

    size = INITCONTEXTLEN + 1; 
    buf = malloc(size);
    if (!buf)
        return -1;
    memset(buf, 0, size);

    ret = _lgetxattr(path, XATTR_NAME_SELINUX, buf, size - 1);
    if (ret < 0 && errno == ERANGE) {
        char *newbuf;

        size = _lgetxattr(path, XATTR_NAME_SELINUX, NULL, 0);
        if (size < 0) 
            goto out;

        size++;
        newbuf = realloc(buf, size);
        if (!newbuf)
            goto out;

        buf = newbuf;
        memset(buf, 0, size);
        ret = _lgetxattr(path, XATTR_NAME_SELINUX, buf, size - 1);
    }  

out:
    if (ret == 0) {
        /* Re-map empty attribute values to errors. */
        errno = EOPNOTSUPP;
        ret = -1;
    }  
    if (ret < 0) 
        free(buf);
    else
        *context = buf;
    return ret;
}
