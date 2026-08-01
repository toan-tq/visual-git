/*
 * Native macOS launcher for visual-git apps.
 *
 * Compiled into Contents/MacOS/<AppName> so macOS recognizes the app bundle
 * identity for TCC (file access permissions) instead of showing "java".
 *
 * Spawns Java as a child process and waits for it to exit.
 *
 * Usage:  clang -DMAIN_CLASS='"com.visualgit.FileExplorer"' -O2 -o AppName launcher.c
 */

#ifndef MAIN_CLASS
#define MAIN_CLASS "com.visualgit.FileExplorer"
#endif

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <libgen.h>
#include <spawn.h>
#include <sys/wait.h>
#include <mach-o/dyld.h>

extern char **environ;

int main(int argc, char *argv[]) {
    /* Find path to this executable → Contents/MacOS/FileExplorer */
    char exe[4096];
    uint32_t size = sizeof(exe);
    if (_NSGetExecutablePath(exe, &size) != 0) {
        fprintf(stderr, "Failed to get executable path\n");
        return 1;
    }
    char real[4096];
    if (!realpath(exe, real)) {
        fprintf(stderr, "Failed to resolve path\n");
        return 1;
    }

    /* Navigate to Contents/Resources/Java */
    char *dir = dirname(real);  /* Contents/MacOS */
    char resources[4096];
    snprintf(resources, sizeof(resources), "%s/../Resources/Java", dir);
    char real_res[4096];
    if (!realpath(resources, real_res)) {
        fprintf(stderr, "Failed to find Resources/Java\n");
        return 1;
    }

    /* Build classpath */
    char cp[8192];
    snprintf(cp, sizeof(cp), "%s/out:%s/lib/swt.jar", real_res, real_res);

    /* Find java binary */
    char java_path[4096] = {0};
    char *java_home = getenv("JAVA_HOME");
    if (java_home && java_home[0]) {
        snprintf(java_path, sizeof(java_path), "%s/bin/java", java_home);
    } else {
        /* Use /usr/libexec/java_home */
        FILE *fp = popen("/usr/libexec/java_home 2>/dev/null", "r");
        if (fp) {
            if (fgets(java_path, sizeof(java_path), fp)) {
                java_path[strcspn(java_path, "\n")] = 0;
                strncat(java_path, "/bin/java", sizeof(java_path) - strlen(java_path) - 1);
            }
            pclose(fp);
        }
        if (java_path[0] == 0) {
            strcpy(java_path, "/usr/bin/java");
        }
    }

    /* Build Java argument list */
    int nargs = argc + 5;
    char **jargs = calloc(nargs + 1, sizeof(char *));
    if (!jargs) {
        fprintf(stderr, "Out of memory\n");
        return 1;
    }
    int j = 0;
    jargs[j++] = java_path;
    jargs[j++] = "-XstartOnFirstThread";
    jargs[j++] = "-cp";
    jargs[j++] = cp;
    jargs[j++] = MAIN_CLASS;
    for (int i = 1; i < argc; i++) {
        jargs[j++] = argv[i];
    }
    jargs[j] = NULL;

    /* Spawn Java as child process (keeps this binary as the responsible process
       so macOS TCC attributes file access to FileExplorer.app, not "java") */
    pid_t pid;
    int err = posix_spawn(&pid, java_path, NULL, NULL, jargs, environ);
    free(jargs);
    if (err != 0) {
        fprintf(stderr, "Failed to launch Java: %s\n", strerror(err));
        return 1;
    }

    int status;
    waitpid(pid, &status, 0);
    return WIFEXITED(status) ? WEXITSTATUS(status) : 1;
}
