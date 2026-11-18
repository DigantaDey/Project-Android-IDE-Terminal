/* Linux host regression for the exact seccomp program shipped to Android.
 * No Android permissions, external network, or companion UID sweep is used. */
#define _GNU_SOURCE
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/ptrace.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>
#include "../native-runtime/src/main/jni/child_policy.h"

#define CHECK(test) do { if (!(test)) { fprintf(stderr, "Failed: %s (errno %d)\n", #test, errno); return 1; } } while (0)
#define DENIED(call, reason) do { errno = 0; CHECK((call) == -1 && errno == (reason)); } while (0)

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    int pair[2];
    CHECK(socketpair(AF_UNIX, SOCK_STREAM, 0, pair) == 0);
    /* A preexisting descriptor must not provide a connect/send-address bypass. */
    int inet = socket(AF_INET, SOCK_STREAM, 0);
    CHECK(inet >= 0);
    int enabled = strcmp(argv[1], "network-on") == 0;
    CHECK(restrict_child(enabled) == 0);
    if (enabled) {
        int fd = socket(AF_INET, SOCK_STREAM, 0);
        CHECK(fd >= 0); close(fd);
        fd = socket(AF_UNIX, SOCK_STREAM, 0);
        CHECK(fd >= 0); close(fd);
    } else {
        DENIED(socket(AF_INET, SOCK_STREAM, 0), EACCES);
        DENIED(socket(AF_INET6, SOCK_STREAM, 0), EACCES);
        DENIED(socket(AF_UNIX, SOCK_STREAM, 0), EACCES);
        DENIED(connect(inet, NULL, 0), EACCES);
        DENIED(sendto(inet, "x", 1, 0, NULL, 0), EACCES);
        DENIED(sendmsg(inet, NULL, 0), EACCES);
        DENIED(sendmmsg(inet, NULL, 0, 0), EACCES);
    }
    close(inet);
    /* Ordinary subprocess and local descriptor I/O are required by Node/Clang. */
    pid_t child = fork();
    CHECK(child >= 0);
    if (!child) { close(pair[0]); _exit(write(pair[1], "42", 2) == 2 ? 0 : 1); }
    close(pair[1]);
    char result[4] = {0};
    CHECK(read(pair[0], result, sizeof(result)) == 2);
    CHECK(strcmp(result, "42") == 0);
    int status;
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    close(pair[0]);
    DENIED(ptrace(PTRACE_TRACEME, 0, NULL, NULL), EPERM);
    DENIED(syscall(__NR_process_vm_readv, getpid(), NULL, 0, NULL, 0, 0), EPERM);
    DENIED(syscall(__NR_process_vm_writev, getpid(), NULL, 0, NULL, 0, 0), EPERM);
    /* BINDER_WRITE_READ's low 16 bits are ABI invariant, including compat ioctl. */
    DENIED(ioctl(-1, 0xc0306201UL, NULL), EPERM);
    DENIED(ioctl(-1, 0xc0186201UL, NULL), EPERM);
    /* Terminal and unrelated ioctls must not match the Binder policy. */
    DENIED(ioctl(-1, TIOCGWINSZ, NULL), EBADF);
#ifdef __NR_io_uring_setup
    DENIED(syscall(__NR_io_uring_setup, 0, NULL), EPERM);
    DENIED(syscall(__NR_io_uring_enter, -1, 0, 0, 0, NULL, 0), EPERM);
    DENIED(syscall(__NR_io_uring_register, -1, 0, NULL, 0), EPERM);
#endif
#if defined(__x86_64__)
    /* x32 must not bypass the native syscall-number checks. */
    DENIED(syscall(0x40000000U | __NR_socket, AF_INET, SOCK_STREAM, 0), EPERM);
#endif
    puts(enabled ? "NETWORK_ON_AND_PROCESS_POLICY_OK" : "NETWORK_OFF_AND_PROCESS_POLICY_OK");
    return 0;
}
