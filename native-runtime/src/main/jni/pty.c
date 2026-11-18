#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <dirent.h>
#include <limits.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <termios.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sys/socket.h>

static void fail(JNIEnv *env, const char *message) {
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), message);
}

static char *string_copy(JNIEnv *env, jstring text) {
    jclass string = (*env)->FindClass(env, "java/lang/String");
    jmethodID bytes_method = (*env)->GetMethodID(env, string, "getBytes", "(Ljava/lang/String;)[B");
    jstring encoding = (*env)->NewStringUTF(env, "UTF-8");
    jbyteArray bytes = (jbyteArray)(*env)->CallObjectMethod(env, text, bytes_method, encoding);
    (*env)->DeleteLocalRef(env, encoding); (*env)->DeleteLocalRef(env, string);
    if (!bytes || (*env)->ExceptionCheck(env)) return NULL;
    jsize size = (*env)->GetArrayLength(env, bytes);
    char *copy = malloc((size_t)size + 1);
    if (copy) { (*env)->GetByteArrayRegion(env, bytes, 0, size, (jbyte *)copy); copy[size] = 0; }
    (*env)->DeleteLocalRef(env, bytes);
    return copy;
}
static char **array_copy(JNIEnv *env, jobjectArray array) {
    jsize size = (*env)->GetArrayLength(env, array);
    char **result = calloc((size_t)size + 1, sizeof(char *));
    if (!result) return NULL;
    for (jsize i = 0; i < size; i++) {
        jstring item = (jstring)(*env)->GetObjectArrayElement(env, array, i);
        result[i] = string_copy(env, item);
        (*env)->DeleteLocalRef(env, item);
        if (!result[i]) { for (jsize j = 0; j < i; j++) free(result[j]); free(result); return NULL; }
    }
    return result;
}
static void free_array(char **array) { if (array) { for (int i = 0; array[i]; i++) free(array[i]); free(array); } }

#if defined(__aarch64__)
#define FORGE_ARCH AUDIT_ARCH_AARCH64
#elif defined(__x86_64__)
#define FORGE_ARCH AUDIT_ARCH_X86_64
#else
#error Unsupported native runtime ABI
#endif
#define ARCH_GUARD \
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)), \
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, FORGE_ARCH, 1, 0), \
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL), \
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)), \
    BPF_JUMP(BPF_JMP | BPF_JSET | BPF_K, 0x40000000, 0, 1), \
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM)

static int restrict_child(int network) {
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0)) return -1;
    struct sock_filter network_filter[] = {
        ARCH_GUARD,
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_socket, 0, 3),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AF_UNIX, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EACCES),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW)
    };
    struct sock_fprog network_program = { .len = sizeof(network_filter) / sizeof(network_filter[0]), .filter = network_filter };
    if (!network && prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &network_program)) return -1;
    /* Prevent a child from inspecting/injecting its trusted companion parent. */
    struct sock_filter process_filter[] = {
        ARCH_GUARD,
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_ptrace, 3, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_process_vm_readv, 2, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_process_vm_writev, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM)
    };
    struct sock_fprog process_program = { .len = sizeof(process_filter) / sizeof(process_filter[0]), .filter = process_filter };
    return prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &process_program);
}

JNIEXPORT jlongArray JNICALL Java_com_forgeide_runtime_NativePty_start(JNIEnv *env, jclass type, jstring directory, jobjectArray arguments, jobjectArray environment, jint rows, jint columns, jboolean network) {
    (void)type;
    char *cwd = string_copy(env, directory);
    char **argv = array_copy(env, arguments), **envp = array_copy(env, environment);
    if (!cwd || !argv || !envp || !argv[0]) { free(cwd); free_array(argv); free_array(envp); fail(env, "Could not allocate native launch data"); return NULL; }
    int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    char slave_name[PATH_MAX];
    if (master < 0 || grantpt(master) || unlockpt(master) || ptsname_r(master, slave_name, sizeof(slave_name))) {
        if (master >= 0) close(master);
        free(cwd); free_array(argv); free_array(envp); fail(env, "Could not open Android PTY"); return NULL;
    }
    struct winsize size = { .ws_row = (unsigned short)rows, .ws_col = (unsigned short)columns };
    ioctl(master, TIOCSWINSZ, &size);
    pid_t pid = fork();
    if (pid == 0) {
        close(master);
        if (setsid() < 0) _exit(126);
        int slave = open(slave_name, O_RDWR);
        if (slave < 0 || ioctl(slave, TIOCSCTTY, 0) < 0) _exit(126);
        if (dup2(slave, 0) < 0 || dup2(slave, 1) < 0 || dup2(slave, 2) < 0 || chdir(cwd)) _exit(126);
        /* No Binder, APK, snapshot, callback, or parent file descriptors survive exec. */
        DIR *fds = opendir("/proc/self/fd");
        if (!fds) _exit(126);
        int keep = dirfd(fds);
        struct dirent *entry;
        while ((entry = readdir(fds))) { char *end; long fd = strtol(entry->d_name, &end, 10); if (!*end && fd > 2 && fd != keep) close((int)fd); }
        closedir(fds);
        struct rlimit no_core = {0, 0}, files = {256, 256}, processes = {64, 64}, file_size = {64 * 1024 * 1024, 64 * 1024 * 1024};
        if (setrlimit(RLIMIT_CORE, &no_core) || setrlimit(RLIMIT_NOFILE, &files) || setrlimit(RLIMIT_NPROC, &processes) || setrlimit(RLIMIT_FSIZE, &file_size) || restrict_child(network)) {
            static const char message[] = "Forge: native restrictions could not be applied; refusing execution\r\n";
            write(2, message, sizeof(message) - 1); _exit(126);
        }
        execve(argv[0], argv, envp);
        static const char message[] = "Forge: native exec failed; check ABI, Android compatibility and bundle integrity\r\n";
        write(2, message, sizeof(message) - 1); _exit(126);
    }
    free(cwd); free_array(argv); free_array(envp);
    if (pid < 0) { close(master); fail(env, "Could not fork native runtime"); return NULL; }
    jlong values[2] = { pid, master };
    jlongArray result = (*env)->NewLongArray(env, 2);
    (*env)->SetLongArrayRegion(env, result, 0, 2, values);
    return result;
}
JNIEXPORT jint JNICALL Java_com_forgeide_runtime_NativePty_waitFor(JNIEnv *env, jclass type, jint pid) {
    (void)env; (void)type;
    int status; pid_t result;
    do { result = waitpid(pid, &status, 0); } while (result < 0 && errno == EINTR);
    if (result < 0) return 137;
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
}
JNIEXPORT void JNICALL Java_com_forgeide_runtime_NativePty_resize(JNIEnv *env, jclass type, jint fd, jint rows, jint columns) {
    (void)env; (void)type;
    struct winsize size = { .ws_row = (unsigned short)rows, .ws_col = (unsigned short)columns };
    ioctl(fd, TIOCSWINSZ, &size);
}
JNIEXPORT void JNICALL Java_com_forgeide_runtime_NativePty_killAll(JNIEnv *env, jclass type) {
    (void)env; (void)type;
    /* Jobs can create new process groups. Sweep our separate runtime UID, never the IDE UID. */
    for (int pass = 0; pass < 8; pass++) {
        DIR *processes = opendir("/proc"); if (!processes) return;
        struct dirent *entry; int found = 0;
        while ((entry = readdir(processes))) {
            char *end; long pid = strtol(entry->d_name, &end, 10);
            if (*end || pid < 1 || pid == getpid()) continue;
            char path[64]; snprintf(path, sizeof(path), "/proc/%ld", pid);
            struct stat info;
            if (!stat(path, &info) && info.st_uid == getuid()) { kill((pid_t)pid, SIGKILL); found = 1; }
        }
        closedir(processes);
        if (!found) break;
    }
}
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm; (void)reserved;
    prctl(PR_SET_DUMPABLE, 0, 0, 0, 0);
    prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0);
    return JNI_VERSION_1_6;
}
